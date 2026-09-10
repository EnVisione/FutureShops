# Economy debugging

FutureShops diagnostics are server side, default off, and intended for a bounded support capture. Server logs and dedicated GameTests are the primary evidence. A client connection is only needed for a named client rendering or synchronization claim.

## Commands

Operators with permission level 2 can run:

```text
/futureshops debug on <module>
/futureshops debug status
/futureshops debug off
```

The server console uses the same commands without the leading slash. Valid modules are `all`, `provider`, `lifecycle`, `transaction`, `receipt`, `recovery`, `pixelmon`, `danconomy`, `vault`, `network`, and `surface`. Enabling the same module is idempotent. `off` is idempotent and stops matching capture within one server tick. Reload, restart, shutdown, timeout, and target removal reset capture.

## Limits and output

One capture lasts at most 60 seconds, accepts at most 100 events per second and 2,000 events total, and reserves at most 5 MiB with a 4 KiB event bound. Status reports the capture UUID, selected module, remaining time, event count, dropped event count, bytes written, limits, and the server log output surface. A limit drops diagnostics only. It never blocks or changes a transaction.

Each schema version 2 `futureshops.debug` event includes source and artifact identity, server platform, provider, lifecycle, operation, request and actor pseudonyms, account class, required capabilities, provider declared capabilities, independently observed account capabilities, validation reason, journal, receipt, custody, claim, typed result, safe next action, sequence, elapsed time, thread, and logical side. Missing account evidence is not converted to true provider capability. Raw UUIDs, player names, paths, NBT, inventories, chat, credentials, and arbitrary configuration are excluded.

## Collection procedure

1. Record the FutureShops source commit, candidate jar SHA-256, Minecraft and NeoForge versions, exact provider and dependency manifest, task ID, and the expected sanitized evidence destination.
2. On a disposable node-1 runtime, write and read back `eula=true` before starting the dedicated server. Inspect the Gradle task graph first and confirm that no client or renderer is started.
3. Start the exact server, wait for readiness, run `futureshops debug status`, then enable only the module needed for the account or persistence claim.
4. Drive the real shop or economy handler with one deterministic request. Record the request alias, fixture account alias, operation, exact minor amount, initial image revision, and expected journal and custody state. Do not bypass permission checks or coordinator routes.
5. Filter the server log by `futureshops.debug`, capture ID, and request alias. Confirm required, declared, and observed capability sets separately. For a bound provider, also confirm the adapter, account class, backend lineage, request fingerprint, and binding validation result. Confirm refusal occurs before intent and custody when binding or capability evidence is missing.
6. Run `futureshops debug off`, inspect status, wait one tick, and repeat the matching stimulus. No new matching event is accepted except an explicitly identified queue flush.
7. Retain only the sanitized packet, decisive log excerpts, command results, test summary, hashes, and unverified claim list. Redact raw identity and private data before sharing.

## Account verdicts

Provider declarations are upper bounds. A bound operation is admissible only when required capabilities intersect with both the provider declaration and independently observed account proof. A custom or hybrid Pixelmon wrapper can be considered only after the exact account class, class loader, descriptors, backend lineage, currency, manager identity, and durable writer protocol are proven. Regular requests use the same binding barrier before the write ahead journal is created. The coordinator persists the binding with each journal and receipt audit transition, then requires the same proof again for lookup and retry. The current convenience binding without runtime proof is intentionally refused.

Legacy records are classified as `LEGACY_COMPATIBLE`, `LEGACY_HYBRID_UNRESOLVED`, or `LEGACY_UNPROVABLE`. Unresolved hybrid and unprovable records remain in recovery or frozen state and are never rebound to the current account.

## Recovery and privacy

Do not delete journals, receipts, custody, claims, account data, or world data to recover a transaction. Stop the server, preserve one complete matching snapshot, and inspect the original provider binding and receipt. An ambiguous external result is never replayed from a local log alone. Follow [backup and restore](operations/backup-restore.md) for restoration.

Phase 004 captures server observable binding and diagnostic facts only. It makes no 2.4.1 release or production hybrid mutation claim. Client layout, input, and reconnect evidence remain a later validation gate.
