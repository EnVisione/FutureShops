# FutureShops economy provider API

The public provider contract is in `com.enviouse.futureshopsp.api.economy`. It is provider neutral and does not expose FutureShops internal economy, Pixelmon, Bukkit, Vault, or bridge classes. A provider may be supplied by another NeoForge mod through the registry contract defined in the active phase plan.

## Compatibility

`EconomyApi.COMPATIBILITY_VERSION` is `1`. A provider must report the same compatibility version before it can be registered. Provider identifiers are lowercase resource identifiers between two and sixty four characters. The identifiers `internal`, `pixelmon`, `danconomy`, and `vault` are reserved by FutureShops.

## Provider contract

`EconomyProvider` exposes the stable identifier, compatibility version, currency metadata, immutable capabilities, lifecycle readiness, balance queries, prechecks, withdrawals, deposits, durable receipt lookup, and idempotent retry. All methods are server authoritative and must execute on the logical server thread. Implementations must not block that thread on unbounded or remote work.

The six capabilities are independent.

| Capability | Meaning |
| --- | --- |
| `BALANCE_QUERY` | A provider can return an authoritative balance. |
| `PRECHECK` | A provider can validate funds and the requested operation without mutating value. |
| `WITHDRAW` | A provider can debit a request amount. |
| `DEPOSIT` | A provider can credit a request amount. |
| `RECEIPT_LOOKUP` | A provider can find the durable outcome for a request identity. |
| `IDEMPOTENT_RETRY` | A provider can repeat the same request identity without duplicating its effect. |

Capabilities are not inferred. A balance query does not imply a mutation, and a boolean mutation result does not prove a receipt or safe retry.

## Registration and selection

Optional integrations call `EconomyProviderRegistry.register` before the registry freezes. Registration is deterministic and rejects invalid identifiers, duplicate identifiers, incompatible arguments, and late calls. The dedicated `registerPixelmon`, `registerDanconomy`, and `registerVault` methods are the only paths for their reserved identifiers. The registry exposes a sorted immutable snapshot and resolves each factory once for a server lifecycle. A factory that throws, reports another identifier, or reports an unsupported compatibility version is unavailable or incompatible.

The server configuration key is `economy.provider`. An absent value selects `internal`. A reload stages a new identifier and reports that a restart is required. It never changes the active identifier during the current server lifecycle. Unknown or malformed identifiers remain selected for diagnostics and do not silently fall back to `internal`.

## Values and requests

Balances and amounts use signed `long` integer minor units. `MutationRequest` requires a positive amount, a server supplied `RequestId`, an actor UUID, an optional counterparty, and a `MutationKind`. Providers must preserve the request identity across their durable operation and receipt records. A locally generated UUID does not make an external operation idempotent unless the external system binds that identity to lookup and replay behavior.

`EconomyAmounts` provides checked addition, subtraction, multiplication, sign validation, and exact decimal parsing. Lossy precision and overflow are rejected before a provider call.

`CurrencyMetadata` validates singular and plural display names and a decimal precision from zero through six. The selected provider owns this metadata for the server lifecycle.

## Results and recovery

`ProviderResult` is explicit. `CONFIRMED` carries a value and, for mutation results, a `MutationReceipt`. `REJECTED`, `UNAVAILABLE`, `AMBIGUOUS`, and `RECOVERY_REQUIRED` carry a typed `ProviderError` and no implicit balance. An unavailable or rejected result may be retried only when the caller has independently established that no external effect occurred. Ambiguous and recovery required results are not safe to retry automatically.

`MutationReceipt` contains the original request identity, mutation kind, amount, provider operation identity, and an optional resulting balance. The optional balance is evidence only and is never a FutureShops shadow ledger.

`lookup(MutationRequest)` is the actor bound recovery entry point. Its default implementation delegates to the original `lookup(RequestId)` method so existing compatibility version `1` providers remain source compatible. Providers whose authoritative account lookup requires the actor must override the request form and verify the full immutable request tuple before returning a receipt.

## Lifecycle

The provider readiness snapshot exposes `UNRESOLVED`, `READY`, `DRAINING`, `MISSING`, `INCOMPATIBLE`, `FAILED`, `RECOVERING`, `FROZEN`, and `STOPPED`. FutureShops admits monetary operations only when the selected provider is ready and the operation's required capabilities are proven. Registration and selection are frozen before monetary readiness. Unclean startup and unknown outcomes require recovery, and unknown outcomes freeze external mutation until an operator resolves them with evidence.

## Server coordinator

`EconomyTransactionCoordinator` is the server side boundary used by the legacy provider view. It checks lifecycle and capabilities before calling a provider, writes `PREPARED` and `EXTERNAL_PENDING` journal records before a mutation, validates the returned receipt, and records `EXTERNAL_CONFIRMED` and `RESOLVED` only for a matching confirmed result. A duplicate request returns the recorded result without a second provider call. An ambiguous or exception result becomes `UNCERTAIN` and freezes the lifecycle. Recovery performs durable receipt lookup before any retry.

Providers that implement `BoundEconomyProvider` receive every regular `MutationRequest` through the same immutable bound operation used by explicit account bindings. The coordinator binds the actor account before precheck, retains the runtime proof for the request, and calls bound precheck, mutation, lookup, and retry methods only after the proof matches. The persisted binding is attached to every pending, confirmed, and resolved journal transition and to the receipt audit record. A missing binding, changed account identity, changed request tuple, or failed rebind returns a typed failure before custody or external mutation. The legacy provider methods remain available for providers without this extension.

Multi step server shop paths use `executeWithCustody`. It runs the same preflight and mutation pipeline while holding a checksummed delivery entitlement first. A confirmed online buy can advance that entitlement to `DELIVERED` and then `CLAIMED`; a sell can retain it as `HELD` until stock settlement and release it only after the credit is confirmed. Rejected outcomes release custody. Ambiguous outcomes retain custody and freeze the lifecycle for evidence based recovery.

The journal is stored as versioned `futureshops_economy_journal` SavedData with checksums, request identity, mutation state, provider result status, diagnostic, and optional receipt. It contains no external balance field. Each transition is also written to the local receipt audit directory at `world/data/futureshops/receipts` with a checksum and provider binding. On an upgrade with a valid existing transaction journal and no receipt directory, the first startup backfills one current audit record per request and performs the normal recovery and clean marker transition. A nonempty or malformed receipt directory is never silently backfilled. The audit record is local evidence of FutureShops state only. It cannot prove that a Vault or Pixelmon effect is safe to replay. Missing, unknown, incomplete, contradictory, or invalid audit records keep startup in `RECOVERING` or `FROZEN` until the originating provider and request outcome are proven. The clean marker is written only after the lifecycle enters `DRAINING` and the journal, receipt audit directory, custody, claims, and checkpoint flush gate succeeds.

Item custody and offline proceeds use separate versioned SavedData indexes. `futureshops_economy_custody` stores a bounded item identity, owner, quantity, content hash, and the monotonic `HELD`, `DELIVERED`, `CLAIMED`, or `RELEASED` state. `futureshops_economy_claims` stores the claimant, exact amount, bounded description, and the non expiring `PENDING`, `DELIVERED`, or `RESOLVED` state. `futureshops_player_shop_barter_escrow` stores exact serialized player shop payment or buyback item stacks and advances them through `PREPARED`, `REMOVED`, `STORED`, and `COMPLETE`, or ends in `REFUNDED` or `RECOVERY_REQUIRED`. `futureshops_player_shop_sale_escrow` stores exact serialized sale output stacks and advances them through `PREPARED`, `REMOVED`, `DELIVERED`, and `CLAIMED`, or ends in `REFUNDED` or `RECOVERY_REQUIRED`. Every record has a checksum and a clean marker. Invalid or newer records fail closed and are not interpreted as completed value movement. Repeated hold, delivery, claim, and resolution calls return the existing terminal record when the immutable request fields match.

`BalanceManager` loads the journal, custody, claims, barter escrow, sale escrow, and settlement indexes before provider readiness, marks them unclean before admission, and includes their integrity and incomplete record state in lifecycle resolution. Shutdown drains the coordinator, flushes all indexes, and writes each clean marker only after the complete flush gate passes. Internal fixtures use in memory implementations only when no world exists, such as unit test servers.

Existing internal provider calls are routed through this boundary. The internal adapter supplies the same typed receipt and retry contract, while unresolved external selections stay unavailable. The bundled Pixelmon 9.4.0 adapter supplies authoritative queries and prechecks through the exact `BankAccountProxy` API. Its native `PlayerPartyStorage` mixin adds durable request receipts and idempotent retry only when the exact native account is returned. An exact `FEBankAccount` from PixelmonEconomyBridge can also use the separately installed `FutureShopsFinalEconomyBridge` service when the pinned FinalEconomy, EverNifeCore, PixelmonEconomyBridge, and Vault versions match. That bridge controls the mutable FinalEconomy wrapper, persists the receipt in the same PlayerData image, and proves force, atomic replacement, directory force, readback, lookup, retry, and conflict behavior before returning confirmation. Custom, generic hybrid, generic Vault, and unmodified accounts remain safely refused.

The bundled DanConomy 1.2.1 adapter accepts only one explicit default currency whose backing type is exactly `LEDGER`. Its mixin stores the new balance and immutable FutureShops receipt in the same `danconomy_ledger` SavedData image, replaces that file durably, reads it back, and acknowledges only after the balance and receipt match. A repeated request returns the existing receipt. A missing default, a `PIXELMON_MIRRORED` currency, malformed receipt data, a conflicting request tuple, a failed durable replacement, or an unverifiable result is unavailable or recovery required with no fallback. The integration is isolated behind runtime class loading and the FutureShops jar does not contain DanConomy classes or its jar.

A separately installed Vault bridge remains subject to the same complete capability and recovery proof before it can register or enable mutations. The test fixture proves that contract with a one transaction SQLite balance and receipt backend, but does not certify the unmodified legacy hybrid stack.

## Account binding contract

`BoundEconomyOperationV1` carries one `PersistedAccountBindingV1` and one non persisted `RuntimeBindingProofV1` through precheck, intent admission, mutation, lookup, retry, and recovery. The persisted binding records schema version, provider and adapter protocol, backend class and fingerprints, durable backend lineage, account UUID, currency and precision, manager identity, binding generation, root and leg request IDs, request fingerprint, and receipt protocol version. Runtime object references are never serialized.

`AccountBindingCodecV1` provides a bounded versioned round trip for persisted bindings. A new wrapper is equivalent to an old wrapper only when all persisted identity and lineage fields match. Manager, account, backend, class loader, currency, generation, or writer exclusion changes return `BINDING_CHANGED` before a new effect. Required capabilities are intersected with provider declared capabilities and independently observed account capabilities. Provider declarations never become account evidence by inference.

`LegacyBindingClassifier` keeps pre binding records explicit. Proven internal, native Pixelmon, DanConomy, and separately proven Vault records use their original compatibility route. An old hybrid Pixelmon record without complete durable identity is `LEGACY_HYBRID_UNRESOLVED` and requires original account proof. Unknown or contradictory records are `LEGACY_UNPROVABLE` and remain frozen. No legacy record is rebound to the currently selected account just to continue a transaction.

The registry, server selection, transaction journal, custody, claims, and surface routing are implemented in their owning phase. This document describes the stable public contract and must be kept aligned with the API source and compatibility tests.
