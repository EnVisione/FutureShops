# Phase 004 security review

Date: 2026-09-09

Scope: The exact PixelmonEconomyBridge FEBankAccount integration, the separately installed FinalEconomy bridge fixture, receipt parsing and persistence, server thread dispatch, and the production jar boundary.

## Threat model

The protected assets are the external PokéDollar balance, the FinalEconomy FEPlayerData image, request identity, immutable receipts, and FutureShops coordinator state. The trust boundaries are the client and network request, the selected Pixelmon account, the Bukkit service boundary, the separately installed plugin class loader, and the FinalEconomy configuration file. Client input, request fields, plugin service results, backend values, and receipt values are treated as untrusted. The production jar must not load Bukkit or package the fixture.

## Findings and remediation

### Numeric conversion truncation, remediated

The bridge previously converted provider `Number` values through `doubleValue()` and receipt values through `longValue()`. Large integers could lose precision and fractional receipt fields could be silently truncated. `FinalEconomyTransactionBridgeAccess` now converts integral types and decimal values through exact `BigDecimal` construction and requires `longValueExact()` for receipt balances. The fixture uses the same exact rule for image revisions and receipt fields. Non finite, fractional, overflowed, or malformed values return a typed unavailable result or corrupt receipt failure before any acknowledgement.

Verification: focused provider tests, full `test`, full `build`, fixture compilation, and exact runtime write and recovery probes.

### Unbounded off thread dispatch, remediated

The public fixture service can receive calls from a worker thread. Dispatch now admits at most 64 outstanding server thread calls with a fair semaphore and returns an unavailable result when full. The watchdog is five seconds. Interruption, timeout, execution failure, and queue saturation never return confirmation.

Verification: source inspection, fixture compilation, server thread dispatch runtime probe, and the existing duplicate and timeout result assertions.

### Symlinked receipt path, remediated

The durable save path now rejects any symbolic link in the normalized target path before directory creation, temporary file creation, or replacement. The write remains same filesystem, forced, atomically replaced, directory forced, and read back before confirmation.

Verification: source inspection and the existing same filesystem force, atomic replacement, readback, and crash stage matrix. A dedicated symlink fault fixture remains part of the Phase 005 production wiring gate.

## Residual risks

Direct writes by unrelated plugins can still invoke FinalEconomy outside the FutureShops service. The wrapper serializes those writes, but they do not receive a FutureShops request receipt because no FutureShops request exists. FutureShops mutations remain request scoped and verify their own before and after image while holding the ledger lock.

The process crash matrix proves the declared file and directory force model. It does not claim protection against an unspecified filesystem or physical power failure. Missing or contradictory external evidence remains typed recovery or ambiguity and never falls back to the internal wallet.

The exact route is allowlisted to the pinned FEBankAccount class and dependency versions. Generic hybrid wrappers, VaultBankAccount, changed class loaders, changed backend lineage, and missing service registration remain refused.

## Secret and artifact checks

The changed source and fixture files contain no credential, token, private key, or secret value. The production jar contains no bridge fixture, Bukkit, Pixelmon, or probe classes. No external jar was modified or packaged into the production artifact.

Commands completed:

```text
rg -n -i "(password|api[_-]?key|access[_-]?token|private[_-]?key|secret)" src/main src/test/fixtures build.gradle
bash ./gradlew test --no-daemon
bash ./gradlew build --no-daemon
```

All commands passed. The exact isolated server GameTest run reported all 32 required tests passed. The exact hybrid runtime probes and crash matrix are recorded in [p004-task-005-2026-09-09.md](p004-task-005-2026-09-09.md).

## Review result

No open high or critical finding remains in the reviewed changed code. Phase 004 still requires its branch integration, signed tag, cursor transition, and the downstream Phase 005 production wiring gates before any release or issue completion claim.
