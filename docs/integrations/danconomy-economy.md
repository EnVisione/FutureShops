# DanConomy economy integration

FutureShops 2.4.1 for NeoForge 1.21.1 includes an optional adapter for exactly DanConomy 1.2.1. The FutureShops jar contains its own adapter and narrow mixin only. DanConomy remains a separately installed, unmodified runtime mod and is not copied, altered, rebuilt, or redistributed. The 2.4.1 artifact remains an unpublished candidate while final validation is open.

## Supported stack

| Component | Required value |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 |
| DanConomy | 1.2.1 |
| FutureShops | 2.4.1 |
| Provider setting | `economy.provider = "danconomy"` |
| Default currency | One explicit registered default |
| Backing type | `LEDGER` |

Selection is restart only. A missing mod, another DanConomy version, no unambiguous default currency, or any backing type other than exact `LEDGER` leaves the selected provider unavailable or incompatible. FutureShops never falls back to `internal` during that lifecycle.

DanConomy `PIXELMON_MIRRORED` currencies are intentionally refused. They do not share DanConomy's ledger persistence path and would create overlapping authority with the native Pixelmon provider. Select `pixelmon` for the exact Pixelmon 9.4.0 native account path.

## Transaction and receipt contract

The adapter reads DanConomy currency identity, singular and plural names, decimal precision, backing type, account balance, and account mutation through runtime isolated access. FutureShops values remain signed integer minor units. Precision and overflow are checked before mutation.

The `LedgerData` mixin adds one bounded, checksummed `FutureShopsReceipts` section to the existing `danconomy_ledger` SavedData. Every accepted request binds all of the following immutable fields.

* Provider identifier.
* FutureShops request UUID.
* Account UUID.
* Currency identifier.
* Mutation kind.
* Amount in minor units.
* Resulting balance in minor units.
* Provider operation identifier.
* Receipt checksum.

Mutation is serialized on the logical server thread. The mixin checks for an existing request before changing the account. An exact duplicate returns the existing receipt. Reuse with another account, currency, kind, or amount is rejected. New balance arithmetic and funds checks complete before state changes.

The changed balance and completed receipt are serialized into the same `danconomy_ledger.dat` image. The replacement uses NeoForge's atomic NBT write, forces the file and containing directory, reads the bounded file back, and verifies the exact balance and receipt before returning `CONFIRMED`. A failure before durable verification returns `RECOVERY_REQUIRED`. A retry first looks up the provider receipt and never reapplies a confirmed effect.

Ordinary DanConomy deposits and withdrawals remain unchanged and do not receive FutureShops request receipts. DanConomy and FutureShops access to this SavedData still runs on the same server thread. Off thread provider calls are refused before SavedData access.

Unknown schemas, malformed entries, invalid checksums, contradictory receipt fields, receipt capacity exhaustion, interrupted durable replacement, and missing evidence fail closed. A nondurable in memory receipt moves provider readiness to `RECOVERING` and blocks every different request until the original request is reconciled. They do not become an inferred balance or automatic retry.

## Operations

Install the exact DanConomy 1.2.1 jar and the same FutureShops 2.4.1 candidate jar on the dedicated server and clients. Configure one explicit default `LEDGER` currency, set `economy.provider = "danconomy"`, and restart. A provider change does not migrate, mirror, seed, or erase balances.

Back up the complete world and `config/futureshops` together. The authoritative DanConomy balance and receipt image is `world/data/danconomy_ledger.dat`. Do not restore that file separately from the matching FutureShops journal, audit receipts, custody, claims, escrows, configuration, and mod set. Follow the [backup and restore runbook](../operations/backup-restore.md).

Enable bounded server diagnostics with:

```text
/futureshops debug on danconomy
/futureshops debug status
/futureshops debug off
```

The diagnostic record includes one session identifier, source and artifact identity, exact runtime versions, lifecycle, provider, operation, request UUID, pseudonymous actor reference, required and observed capabilities, validation result, provider status, sanitized error, server side, thread, and next action. It does not print balances, credentials, private paths, or raw player data.

## Development verification

The external jar is an optional compile only and runtime test input. It is never a normal packaged dependency.

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew compileJava --no-daemon -PdanconomyJar=/path/to/danconomy-1.2.1.jar
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 bash ./gradlew runGameTestServer --no-daemon -PdanconomyJar=/path/to/danconomy-1.2.1.jar -PverificationGameDirectory=/path/to/disposable/runtime
```

The build accepts only SHA 256 `61d3eb69a3a235929ac2376d151130e61ea4fe65c2f84990618c79e27e954b72` for the optional DanConomy input. The reviewed source revision is `63aecdac12e437ae1f3de2801cdea0105b3d7e06`. The release jar SHA 512 is `865aba88f26d1a78ec92b4981f9a9b5af701a5f62cb38904d9439138f7a95ac740ce80d6e717b95169e4df6e07bac3893a90ae78de8565e9c168c8b0190713f0`.

Exact target inspection and runtime results are recorded in [DanConomy target evidence](../verification/phase-002/danconomy-target-2026-09-05.md) and [DanConomy runtime evidence](../verification/phase-002/danconomy-runtime-2026-09-05.md).
