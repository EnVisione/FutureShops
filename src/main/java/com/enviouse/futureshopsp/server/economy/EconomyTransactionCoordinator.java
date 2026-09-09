package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.BoundEconomyOperationV1;
import com.enviouse.futureshopsp.api.economy.EconomyCapability;
import com.enviouse.futureshopsp.api.economy.EconomyProvider;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.OperationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderCapabilities;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderLifecycle;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import com.enviouse.futureshopsp.api.economy.RequiredCapabilities;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.api.economy.RuntimeBindingProofV1;
import com.enviouse.futureshopsp.server.debug.DebugDiagnostics;
import com.enviouse.futureshopsp.server.debug.DebugModule;
import com.enviouse.futureshopsp.event.BalanceChangeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Objects;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One server authoritative transaction boundary for provider reads and mutations.
 * The journal is written before a provider effect and every ambiguous outcome freezes admission.
 */
public final class EconomyTransactionCoordinator {
    private final EconomyProvider provider;
    private final EconomyLifecycleController lifecycle;
    private final EconomyTransactionJournal journal;
    private final EconomyCustodyStore custody;
    private final EconomyClaimStore claims;
    private final EconomyReceiptAuditJournal receiptAudit;
    private final Object lock = new Object();
    private final Map<RequestId, BoundEconomyOperationV1> bindings = new HashMap<>();

    public EconomyTransactionCoordinator(EconomyProvider provider,
                                         EconomyLifecycleController lifecycle,
                                         EconomyTransactionJournal journal) {
        this(provider, lifecycle, journal, new InMemoryEconomyCustodyStore(), new InMemoryEconomyClaimStore());
    }

    public EconomyTransactionCoordinator(EconomyProvider provider,
                                         EconomyLifecycleController lifecycle,
                                         EconomyTransactionJournal journal,
                                         EconomyCustodyStore custody,
                                         EconomyClaimStore claims) {
        this(provider, lifecycle, journal, custody, claims, new InMemoryEconomyReceiptAuditJournal());
    }

    public EconomyTransactionCoordinator(EconomyProvider provider,
                                         EconomyLifecycleController lifecycle,
                                         EconomyTransactionJournal journal,
                                         EconomyCustodyStore custody,
                                         EconomyClaimStore claims,
                                         EconomyReceiptAuditJournal receiptAudit) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.custody = Objects.requireNonNull(custody, "custody");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.receiptAudit = Objects.requireNonNull(receiptAudit, "receiptAudit");
    }

    public EconomyLifecycleSnapshot lifecycle() {
        return lifecycle.snapshot();
    }

    /**
     * Creates a binding with no account proof. The returned operation is intentionally not
     * admissible until an adapter supplies an independently observed runtime proof.
     */
    public BoundEconomyOperationV1 bind(OperationRequest request, UUID actorId,
                                        RequiredCapabilities required) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(required, "required");
        if (!actorId.equals(request.actor())) {
            throw new IllegalArgumentException("operation actor does not match request");
        }
        MutationRequest mutation = request.mutationRequest();
        String providerClass = provider.getClass().getName();
        String providerId = provider.providerId();
        String backendFingerprint = EconomyRecordChecksum.sha256(providerClass);
        String requestFingerprint = EconomyRecordChecksum.sha256(
                request.requestId().value() + "|" + request.actor() + "|" + request.counterparty()
                        + "|" + request.amountMinorUnits() + "|" + request.kind() + "|" + request.operation());
        PersistedAccountBindingV1 persisted = new PersistedAccountBindingV1(
                providerId,
                provider.compatibilityVersion(),
                providerId,
                "provider-api-v" + provider.compatibilityVersion(),
                providerClass,
                backendFingerprint,
                Optional.empty(),
                "provider:" + providerId + ":" + backendFingerprint,
                actorId,
                provider.currency().singularName(),
                provider.currency().decimalPlaces(),
                providerClass,
                0L,
                request.requestId(),
                request.requestId(),
                requestFingerprint,
                1);
        RuntimeBindingProofV1 proof = new RuntimeBindingProofV1(providerClass,
                provider.getClass().getClassLoader() == null ? "bootstrap" : provider.getClass().getClassLoader().toString(),
                Set.of(providerClass), null, null, null, 0L, ProviderCapabilities.none(),
                "account proof not supplied");
        return bind(new BoundEconomyOperationV1(mutation, actorId, required.value(), persisted, proof));
    }

    /** Registers an adapter supplied binding once and rejects any changed identity for its UUID. */
    public BoundEconomyOperationV1 bind(BoundEconomyOperationV1 operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (lock) {
            BoundEconomyOperationV1 existing = bindings.get(operation.request().requestId());
            if (existing != null && !sameBinding(existing, operation)) {
                throw new IllegalStateException("REQUEST_CONFLICT: bound operation identity changed");
            }
            bindings.putIfAbsent(operation.request().requestId(), operation);
            return bindings.get(operation.request().requestId());
        }
    }

    /** Returns the immutable binding currently admitted for a request, if one exists. */
    public Optional<BoundEconomyOperationV1> binding(RequestId requestId) {
        Objects.requireNonNull(requestId, "requestId");
        synchronized (lock) {
            return Optional.ofNullable(bindings.get(requestId));
        }
    }

    /** Runs precheck only after the same immutable account binding is still present and valid. */
    public ProviderResult<BalanceSnapshot> preflight(BoundEconomyOperationV1 operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (lock) {
            ProviderResult<BalanceSnapshot> bindingResult = validateBinding(operation);
            if (bindingResult != null) {
                return bindingResult;
            }
            return preflightInternal(operation.request());
        }
    }

    /** Compatibility spelling for adapters that call the operation gate precheck. */
    public ProviderResult<BalanceSnapshot> precheck(BoundEconomyOperationV1 operation) {
        return preflight(operation);
    }

    public ProviderResult<MutationReceipt> withdraw(BoundEconomyOperationV1 operation) {
        return executeBound(operation, MutationKind.WITHDRAW);
    }

    public ProviderResult<MutationReceipt> deposit(BoundEconomyOperationV1 operation) {
        return executeBound(operation, MutationKind.DEPOSIT);
    }

    /** Routes a bound operation through its declared mutation kind without resolving a new account. */
    public ProviderResult<MutationReceipt> mutate(BoundEconomyOperationV1 operation,
                                                   MutationRequest request) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(request, "request");
        if (!requestMatches(operation.request(), request)) {
            return ProviderResult.rejected(ProviderError.REQUEST_CONFLICT,
                    "mutation request conflicts with the bound operation");
        }
        return executeBound(operation, request.kind());
    }

    public ProviderResult<MutationReceipt> lookup(BoundEconomyOperationV1 operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (lock) {
            ProviderResult<BalanceSnapshot> bindingResult = validateBinding(operation);
            if (bindingResult != null) {
                return copyFailure(bindingResult);
            }
            try {
                return provider.lookup(operation.request());
            } catch (RuntimeException exception) {
                return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "bound provider lookup failed");
            }
        }
    }

    public ProviderResult<MutationReceipt> retry(BoundEconomyOperationV1 operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (lock) {
            ProviderResult<BalanceSnapshot> bindingResult = validateBinding(operation);
            if (bindingResult != null) {
                return copyFailure(bindingResult);
            }
            try {
                return provider.retry(operation.request());
            } catch (RuntimeException exception) {
                return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "bound provider retry failed");
            }
        }
    }

    /** Freezes admission when a confirmed provider leg cannot be finalized locally. */
    public void markRecoveryRequired(String diagnostic) {
        lifecycle.markAmbiguous(diagnostic == null || diagnostic.isBlank()
                ? "economy recovery is required"
                : diagnostic);
    }

    public Optional<CustodyRecord> custody(RequestId requestId) {
        return custody.find(Objects.requireNonNull(requestId, "requestId"));
    }

    public CustodyRecord holdCustody(RequestId requestId, UUID owner, String itemKey,
                                     long quantity, String contentHash) {
        requireReadyMutation();
        synchronized (lock) {
            Optional<CustodyRecord> existing = custody.find(requestId);
            if (existing.isPresent()) {
                CustodyRecord record = existing.orElseThrow();
                if (!record.owner().equals(owner) || !record.itemKey().equals(itemKey)
                        || record.quantity() != quantity || !record.contentHash().equals(contentHash)) {
                    throw new IllegalStateException("custody request conflicts with existing record");
                }
                return record;
            }
            return custody.hold(requestId, owner, itemKey, quantity, contentHash);
        }
    }

    public CustodyRecord deliverCustody(RequestId requestId) {
        requireCustodyAccess();
        synchronized (lock) {
            CustodyRecord current = custody.find(requestId).orElseThrow(() ->
                    new IllegalStateException("custody does not exist"));
            if (current.state() == CustodyState.DELIVERED || current.state() == CustodyState.CLAIMED) {
                return current;
            }
            return custody.transition(requestId, CustodyState.HELD, CustodyState.DELIVERED);
        }
    }

    public CustodyRecord claimCustody(RequestId requestId) {
        requireCustodyAccess();
        synchronized (lock) {
            CustodyRecord current = custody.find(requestId).orElseThrow(() ->
                    new IllegalStateException("custody does not exist"));
            if (current.state() == CustodyState.CLAIMED) {
                return current;
            }
            return custody.transition(requestId, CustodyState.DELIVERED, CustodyState.CLAIMED);
        }
    }

    public CustodyRecord releaseCustody(RequestId requestId) {
        requireCustodyAccess();
        synchronized (lock) {
            CustodyRecord current = custody.find(requestId).orElseThrow(() ->
                    new IllegalStateException("custody does not exist"));
            if (current.state() == CustodyState.RELEASED) {
                return current;
            }
            return custody.transition(requestId, CustodyState.HELD, CustodyState.RELEASED);
        }
    }

    public Optional<ClaimRecord> claim(RequestId requestId) {
        return claims.find(Objects.requireNonNull(requestId, "requestId"));
    }

    public ClaimRecord createClaim(RequestId requestId, UUID claimant, long amountMinorUnits, String description) {
        requireCustodyAccess();
        synchronized (lock) {
            Optional<ClaimRecord> existing = claims.find(requestId);
            if (existing.isPresent()) {
                ClaimRecord record = existing.orElseThrow();
                if (!record.claimant().equals(claimant) || record.amountMinorUnits() != amountMinorUnits
                        || !record.description().equals(description == null ? "" : description.trim())) {
                    throw new IllegalStateException("claim request conflicts with existing record");
                }
                return record;
            }
            if (!lifecycle.admitMutation()) {
                throw new IllegalStateException("economy mutations are not ready");
            }
            return claims.create(requestId, claimant, amountMinorUnits, description);
        }
    }

    public ClaimRecord deliverClaim(RequestId requestId) {
        requireCustodyAccess();
        synchronized (lock) {
            ClaimRecord current = claims.find(requestId).orElseThrow(() ->
                    new IllegalStateException("claim does not exist"));
            if (current.state() == ClaimState.DELIVERED || current.state() == ClaimState.RESOLVED) {
                return current;
            }
            return claims.transition(requestId, ClaimState.PENDING, ClaimState.DELIVERED);
        }
    }

    public ClaimRecord resolveClaim(RequestId requestId) {
        requireCustodyAccess();
        synchronized (lock) {
            ClaimRecord current = claims.find(requestId).orElseThrow(() ->
                    new IllegalStateException("claim does not exist"));
            if (current.state() == ClaimState.RESOLVED) {
                return current;
            }
            return claims.transition(requestId, current.state(), ClaimState.RESOLVED);
        }
    }

    public ProviderResult<BalanceSnapshot> balance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!lifecycle.admitQuery()) {
            return unavailableForLifecycle();
        }
        if (!supports(EconomyCapability.BALANCE_QUERY)) {
            return ProviderResult.unavailable(ProviderError.CAPABILITY_MISSING,
                    "provider does not support balance queries");
        }
        try {
            ProviderResult<BalanceSnapshot> result = provider.balance(playerId);
            return result == null ? ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "provider returned no balance result") : result;
        } catch (RuntimeException exception) {
            lifecycle.markFailed("balance query failed");
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION, "balance query failed");
        }
    }

    public ProviderResult<BalanceSnapshot> preflight(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            return preflightInternal(request);
        }
    }

    public ProviderResult<MutationReceipt> withdraw(MutationRequest request) {
        return execute(request, MutationKind.WITHDRAW);
    }

    public ProviderResult<MutationReceipt> deposit(MutationRequest request) {
        return execute(request, MutationKind.DEPOSIT);
    }

    /** Executes one durable refund leg with its own request identity. */
    public ProviderResult<MutationReceipt> refund(MutationRequest request) {
        return execute(request, MutationKind.REFUND);
    }

    /** Executes one durable compensation leg with its own request identity. */
    public ProviderResult<MutationReceipt> compensate(MutationRequest request) {
        return execute(request, MutationKind.COMPENSATION);
    }

    public ProviderResult<MutationReceipt> executeWithCustody(MutationRequest request, UUID owner,
                                                               String itemKey, long quantity,
                                                               String contentHash, CustodyState terminalState) {
        return executeWithCustody(request, owner, itemKey, quantity, contentHash, terminalState, true);
    }

    /**
     * Executes a custodied provider leg with an explicit definitive-failure custody policy.
     * Callers that retain custody must restore or resolve it after a proven provider rejection.
     */
    public ProviderResult<MutationReceipt> executeWithCustody(MutationRequest request, UUID owner,
                                                               String itemKey, long quantity,
                                                               String contentHash, CustodyState terminalState,
                                                               boolean releaseCustodyOnDefinitiveFailure) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(terminalState, "terminalState");
        if (terminalState != CustodyState.HELD && terminalState != CustodyState.DELIVERED && terminalState != CustodyState.CLAIMED
                && terminalState != CustodyState.RELEASED) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "invalid custody terminal state");
        }
        synchronized (lock) {
            EconomyJournalRecord existing;
            try {
                existing = journal.find(request.requestId()).orElse(null);
            } catch (RuntimeException exception) {
                return journalFailure("transaction journal lookup failed");
            }
            if (existing != null) {
                if (!providerMatches(existing)) {
                    lifecycle.markAmbiguous("transaction is bound to another provider");
                    return ProviderResult.recoveryRequired("transaction provider binding requires recovery");
                }
                if (!requestMatches(request, existing.request())) {
                    return ProviderResult.rejected(ProviderError.INVALID_REQUEST,
                            "transaction request conflicts with the persisted identity");
                }
                ProviderResult<MutationReceipt> replayed = replay(existing);
                if (replayed.confirmed()) {
                    CustodyRecord custodyRecord;
                    try {
                        custodyRecord = custody.find(request.requestId().child("custody")).orElse(null);
                    } catch (RuntimeException exception) {
                        return journalFailure("custody lookup failed during replay");
                    }
                    if (custodyRecord == null) {
                        return journalFailure("custody finalization requires recovery");
                    }
                    if (!custodyMatches(custodyRecord, owner, itemKey, quantity, contentHash)) {
                        return ProviderResult.rejected(ProviderError.INVALID_REQUEST,
                                "custody request conflicts with the persisted identity");
                    }
                    if (!custodyTerminalStateMatches(custodyRecord.state(), terminalState)) {
                        return journalFailure("custody finalization requires recovery");
                    }
                }
                return replayed;
            }
            ProviderResult<BalanceSnapshot> preflight = preflightInternal(request);
            if (!preflight.confirmed()) {
                return copyFailure(preflight);
            }
            EconomyJournalRecord prepared = new EconomyJournalRecord(request,
                    EconomyTransactionState.PREPARED, Optional.empty(), ProviderResultStatus.REJECTED, "",
                    provider.providerId());
            try {
                append(prepared);
            } catch (RuntimeException exception) {
                return journalFailure("transaction intent could not be persisted");
            }
            RequestId custodyId = request.requestId().child("custody");
            try {
                holdCustody(custodyId, owner, itemKey, quantity, contentHash);
            } catch (RuntimeException exception) {
                try {
                    replace(prepared, EconomyTransactionState.RESOLVED, Optional.empty(),
                            ProviderResultStatus.REJECTED, "custody could not be persisted");
                } catch (RuntimeException ignored) {
                    return journalFailure("custody and transaction state require recovery");
                }
                return journalFailure("custody persistence failed before provider mutation");
            }
            ProviderResult<MutationReceipt> result = executeAfterPrepared(request, request.kind());
            if (!result.confirmed()) {
                if (releaseCustodyOnDefinitiveFailure
                        && result.status() != ProviderResultStatus.AMBIGUOUS
                        && result.status() != ProviderResultStatus.RECOVERY_REQUIRED) {
                    try {
                        releaseCustody(custodyId);
                    } catch (RuntimeException exception) {
                        lifecycle.markAmbiguous("custody release failed after provider rejection");
                        return ProviderResult.recoveryRequired("custody release requires recovery");
                    }
                }
                return result;
            }
            try {
                if (terminalState == CustodyState.HELD) {
                    return result;
                }
                if (terminalState == CustodyState.CLAIMED) {
                    deliverCustody(custodyId);
                    claimCustody(custodyId);
                } else if (terminalState == CustodyState.DELIVERED) {
                    deliverCustody(custodyId);
                } else {
                    releaseCustody(custodyId);
                }
            } catch (RuntimeException exception) {
                lifecycle.markAmbiguous("custody finalization failed after provider confirmation");
                return ProviderResult.recoveryRequired("custody finalization requires recovery");
            }
            return result;
        }
    }

    public ProviderResult<MutationReceipt> transfer(UUID from, UUID to, long amountMinorUnits) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "transfer target must differ");
        }
        if (!supportsAllMutationCapabilities()) {
            return ProviderResult.unavailable(ProviderError.CAPABILITY_MISSING,
                    "provider lacks the capabilities required for an atomic transfer");
        }
        RequestId root = RequestId.random();
        MutationRequest debit = new MutationRequest(root.child("transfer debit"), from, Optional.of(to), amountMinorUnits,
                MutationKind.TRANSFER_DEBIT);
        ProviderResult<MutationReceipt> debitResult = execute(debit, MutationKind.TRANSFER_DEBIT);
        if (!debitResult.confirmed()) {
            return debitResult;
        }
        MutationRequest credit = new MutationRequest(root.child("transfer credit"), to, Optional.of(from), amountMinorUnits,
                MutationKind.TRANSFER_CREDIT);
        ProviderResult<MutationReceipt> creditResult = execute(credit, MutationKind.TRANSFER_CREDIT);
        if (creditResult.confirmed()) {
            return debitResult;
        }
        MutationRequest compensation = new MutationRequest(root.child("transfer compensation"), from, Optional.of(to),
                amountMinorUnits, MutationKind.COMPENSATION);
        ProviderResult<MutationReceipt> compensationResult = compensate(compensation);
        if (!compensationResult.confirmed()) {
            lifecycle.markAmbiguous("transfer compensation requires recovery");
            return ProviderResult.recoveryRequired("transfer compensation requires recovery");
        }
        return creditResult;
    }

    public ProviderResult<MutationReceipt> recover(RequestId requestId) {
        Objects.requireNonNull(requestId, "requestId");
        synchronized (lock) {
            EconomyJournalRecord record;
            try {
                record = journal.find(requestId).orElse(null);
            } catch (RuntimeException exception) {
                return journalFailure("transaction journal lookup failed during recovery");
            }
            if (record == null) {
                return ProviderResult.rejected(ProviderError.RECEIPT_NOT_FOUND, "transaction is not journaled");
            }
            if (!providerMatches(record)) {
                lifecycle.markAmbiguous("transaction is bound to another provider");
                return ProviderResult.recoveryRequired("transaction provider binding requires recovery");
            }
            if (LegacyBindingClassifier.classify(record)
                    == com.enviouse.futureshopsp.api.economy.LegacyBindingClassification.LEGACY_HYBRID_UNRESOLVED
                    && !bindings.containsKey(requestId)) {
                lifecycle.markAmbiguous("legacy hybrid account binding is unresolved");
                return ProviderResult.recoveryRequired("legacy hybrid account binding requires original proof");
            }
            if (!record.incomplete()) {
                if (record.resultStatus() == ProviderResultStatus.CONFIRMED) {
                    MutationReceipt receipt = record.receipt().orElse(null);
                    if (!validReceipt(record.request(), receipt)) {
                        lifecycle.markAmbiguous("terminal transaction receipt is invalid");
                        return ProviderResult.recoveryRequired("terminal transaction receipt is invalid");
                    }
                    return ProviderResult.confirmed(receipt);
                }
                if (record.resultStatus() == ProviderResultStatus.REJECTED && record.receipt().isEmpty()) {
                    return ProviderResult.rejected(ProviderError.DUPLICATE_REQUEST,
                            "transaction is already resolved");
                }
                lifecycle.markAmbiguous("terminal transaction outcome is inconsistent");
                return ProviderResult.recoveryRequired("terminal transaction outcome is inconsistent");
            }
            if (!supports(EconomyCapability.RECEIPT_LOOKUP)) {
                lifecycle.markAmbiguous("provider cannot look up pending transaction");
                return ProviderResult.recoveryRequired("durable receipt lookup is unavailable");
            }
            ProviderResult<MutationReceipt> lookup;
            try {
                lookup = provider.lookup(record.request());
            } catch (RuntimeException exception) {
                return markUncertainOrFreeze(record, "receipt lookup failed");
            }
            MutationReceipt recoveredReceipt = lookup == null ? null
                    : lookup.receipt().orElse(lookup.value().orElse(null));
            if (lookup != null && lookup.confirmed() && validReceipt(record.request(), recoveredReceipt)) {
                try {
                    replace(record, EconomyTransactionState.EXTERNAL_CONFIRMED, Optional.of(recoveredReceipt),
                            ProviderResultStatus.CONFIRMED, "");
                    replace(new EconomyJournalRecord(record.request(), EconomyTransactionState.EXTERNAL_CONFIRMED,
                                    Optional.of(recoveredReceipt), ProviderResultStatus.CONFIRMED, "", provider.providerId()),
                            EconomyTransactionState.RESOLVED, Optional.of(recoveredReceipt),
                            ProviderResultStatus.CONFIRMED, "");
                } catch (RuntimeException exception) {
                    return journalFailure("recovered provider outcome could not be finalized");
                }
                publishConfirmedBalanceChange(record.request(), recoveredReceipt);
                lifecycle.markRecovered();
                return lookup;
            }
            if (lookup != null && lookup.status() == ProviderResultStatus.REJECTED
                    && lookup.error() == ProviderError.RECEIPT_NOT_FOUND
                    && supports(EconomyCapability.IDEMPOTENT_RETRY)) {
                ProviderResult<MutationReceipt> retry;
                try {
                    retry = provider.retry(record.request());
                } catch (RuntimeException exception) {
                    return markUncertainOrFreeze(record, "idempotent provider retry failed");
                }
                MutationReceipt retryReceipt = retry == null ? null
                        : retry.receipt().orElse(retry.value().orElse(null));
                if (retry != null && retry.confirmed() && validReceipt(record.request(), retryReceipt)) {
                    try {
                        replace(record, EconomyTransactionState.EXTERNAL_CONFIRMED, Optional.of(retryReceipt),
                                ProviderResultStatus.CONFIRMED, "");
                        replace(new EconomyJournalRecord(record.request(), EconomyTransactionState.EXTERNAL_CONFIRMED,
                                        Optional.of(retryReceipt), ProviderResultStatus.CONFIRMED, "", provider.providerId()),
                                EconomyTransactionState.RESOLVED, Optional.of(retryReceipt),
                                ProviderResultStatus.CONFIRMED, "");
                    } catch (RuntimeException exception) {
                        return journalFailure("retried provider outcome could not be finalized");
                    }
                    publishConfirmedBalanceChange(record.request(), retryReceipt);
                    lifecycle.markRecovered();
                    return retry;
                }
                if (retry != null && retry.status() == ProviderResultStatus.REJECTED) {
                    try {
                        replace(record, EconomyTransactionState.RESOLVED, Optional.empty(),
                                ProviderResultStatus.REJECTED, retry.diagnostic());
                    } catch (RuntimeException exception) {
                        return journalFailure("retried provider rejection could not be persisted");
                    }
                    lifecycle.markRecovered();
                    return retry;
                }
                return markUncertainOrFreeze(record, "idempotent provider retry remains unknown");
            }
            if (lookup != null && lookup.status() == ProviderResultStatus.REJECTED
                    && lookup.error() != ProviderError.RECEIPT_NOT_FOUND) {
                try {
                    replace(record, EconomyTransactionState.RESOLVED, Optional.empty(),
                            ProviderResultStatus.REJECTED, lookup.diagnostic());
                } catch (RuntimeException exception) {
                    return journalFailure("rejected provider outcome could not be persisted");
                }
                lifecycle.markRecovered();
                return lookup;
            }
            return markUncertainOrFreeze(record, "provider outcome remains unknown");
        }
    }

    private ProviderResult<MutationReceipt> execute(MutationRequest request, MutationKind expectedKind) {
        Objects.requireNonNull(request, "request");
        if (request.kind() != expectedKind && !(expectedKind == MutationKind.COMPENSATION
                && request.kind() == MutationKind.COMPENSATION)) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "mutation kind does not match route");
        }
        synchronized (lock) {
            EconomyJournalRecord existing;
            try {
                existing = journal.find(request.requestId()).orElse(null);
            } catch (RuntimeException exception) {
                return journalFailure("transaction journal lookup failed");
            }
            if (existing != null) {
                if (!providerMatches(existing)) {
                    lifecycle.markAmbiguous("transaction is bound to another provider");
                    return ProviderResult.recoveryRequired("transaction provider binding requires recovery");
                }
                if (!requestMatches(request, existing.request())) {
                    return ProviderResult.rejected(ProviderError.INVALID_REQUEST,
                            "transaction request conflicts with the persisted identity");
                }
                return replay(existing);
            }
            ProviderResult<MutationReceipt> admission = admit(request);
            if (admission != null) {
                return admission;
            }
            EconomyJournalRecord prepared = new EconomyJournalRecord(request,
                    EconomyTransactionState.PREPARED, Optional.empty(), ProviderResultStatus.REJECTED, "",
                    provider.providerId());
            try {
                append(prepared);
            } catch (RuntimeException exception) {
                return journalFailure("transaction intent could not be persisted");
            }
            return executeAfterPrepared(request, expectedKind);
        }
    }

    private ProviderResult<MutationReceipt> executeAfterPrepared(MutationRequest request, MutationKind expectedKind) {
        EconomyJournalRecord pending = new EconomyJournalRecord(request,
                EconomyTransactionState.EXTERNAL_PENDING, Optional.empty(), ProviderResultStatus.UNAVAILABLE, "",
                provider.providerId());
        try {
            journal.replace(pending);
            if (!journal.flush()) {
                throw new IllegalStateException("transaction journal flush failed");
            }
            receiptAudit.append(pending);
            if (!receiptAudit.flush()) {
                throw new IllegalStateException("receipt audit flush failed");
            }
        } catch (RuntimeException exception) {
            return journalFailure("pending transaction state could not be persisted");
        }

        ProviderResult<MutationReceipt> result;
        try {
            result = expectedKind == MutationKind.DEPOSIT || expectedKind == MutationKind.TRANSFER_CREDIT
                    || expectedKind == MutationKind.REFUND || expectedKind == MutationKind.COMPENSATION
                    ? provider.deposit(request) : provider.withdraw(request);
        } catch (RuntimeException exception) {
            return ambiguous(pending, "provider mutation failed after pending state");
        }
        if (result == null) {
            return ambiguous(pending, "provider returned no mutation result");
        }
        DebugDiagnostics.transaction(DebugModule.TRANSACTION, "economy", expectedKind.name().toLowerCase(), request,
                null, provider.capabilities(), result, pending.state().name(), "provider_result", "unknown", "unknown",
                result.confirmed() ? "persist confirmed receipt" : "follow the typed provider outcome");
        if (result.confirmed()) {
            MutationReceipt receipt = result.receipt().orElse(result.value().orElse(null));
            if (!validReceipt(request, receipt)) {
                return ambiguous(pending, "provider receipt does not match request");
            }
            try {
                replace(pending, EconomyTransactionState.EXTERNAL_CONFIRMED, Optional.of(receipt),
                        ProviderResultStatus.CONFIRMED, "");
            } catch (RuntimeException exception) {
                return journalFailure("confirmed provider outcome could not be persisted");
            }
            try {
                replace(new EconomyJournalRecord(request, EconomyTransactionState.EXTERNAL_CONFIRMED,
                                Optional.of(receipt), ProviderResultStatus.CONFIRMED, "", provider.providerId()),
                        EconomyTransactionState.RESOLVED, Optional.of(receipt),
                        ProviderResultStatus.CONFIRMED, "");
            } catch (RuntimeException exception) {
                return journalFailure("confirmed provider outcome could not be finalized");
            }
            publishConfirmedBalanceChange(request, receipt);
            return ProviderResult.confirmed(receipt);
        }
        if (result.status() == ProviderResultStatus.REJECTED) {
            try {
                replace(pending, EconomyTransactionState.RESOLVED, Optional.empty(),
                        ProviderResultStatus.REJECTED, result.diagnostic());
            } catch (RuntimeException exception) {
                return journalFailure("rejected provider outcome could not be persisted");
            }
            return result;
        }
        return ambiguous(pending, result.diagnostic().isBlank()
                ? "provider outcome is not definitive" : result.diagnostic());
    }

    private ProviderResult<MutationReceipt> admit(MutationRequest request) {
        ProviderResult<BalanceSnapshot> preflight = preflightInternal(request);
        return preflight.confirmed() ? null : copyFailure(preflight);
    }

    private ProviderResult<BalanceSnapshot> preflightInternal(MutationRequest request) {
        EconomyLifecycleSnapshot state = lifecycle.snapshot();
        if (!state.acceptsMutations()) {
            if (state.lifecycle() == ProviderLifecycle.RECOVERING || state.lifecycle() == ProviderLifecycle.FROZEN) {
                return ProviderResult.recoveryRequired(state.diagnostic());
            }
            return ProviderResult.unavailable(ProviderError.NOT_READY,
                    state.diagnostic().isBlank() ? "provider is not ready" : state.diagnostic());
        }
        if (!supports(EconomyCapability.PRECHECK)
                || !supports(EconomyCapability.RECEIPT_LOOKUP)
                || !supports(EconomyCapability.IDEMPOTENT_RETRY)
                || !supports(request.kind() == MutationKind.DEPOSIT || request.kind() == MutationKind.TRANSFER_CREDIT
                || request.kind() == MutationKind.REFUND || request.kind() == MutationKind.COMPENSATION
                ? EconomyCapability.DEPOSIT : EconomyCapability.WITHDRAW)) {
            return ProviderResult.unavailable(ProviderError.CAPABILITY_MISSING,
                    "provider lacks the capabilities required by this mutation");
        }
        ProviderResult<BalanceSnapshot> precheck;
        try {
            precheck = provider.precheck(request);
        } catch (RuntimeException exception) {
            lifecycle.markFailed("provider precheck failed");
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION, "provider precheck failed");
        }
        if (precheck == null) {
            lifecycle.markFailed("provider returned no precheck result");
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION, "provider returned no precheck result");
        }
        if (!precheck.confirmed()) {
            return precheck;
        }
        return precheck;
    }

    private void requireReadyMutation() {
        if (!lifecycle.admitMutation()) {
            throw new IllegalStateException("economy mutations are not ready");
        }
    }

    private void requireCustodyAccess() {
        if (lifecycle.snapshot().lifecycle() == ProviderLifecycle.STOPPED) {
            throw new IllegalStateException("economy custody is stopped");
        }
    }

    private ProviderResult<MutationReceipt> replay(EconomyJournalRecord record) {
        if (record.state() == EconomyTransactionState.RESOLVED
                || record.state() == EconomyTransactionState.EXTERNAL_CONFIRMED) {
            if (record.resultStatus() == ProviderResultStatus.CONFIRMED && record.receipt().isEmpty()) {
                lifecycle.markAmbiguous("confirmed transaction receipt is missing");
                return ProviderResult.recoveryRequired("confirmed transaction receipt is missing");
            }
            if (record.resultStatus() == ProviderResultStatus.CONFIRMED) {
                MutationReceipt receipt = record.receipt().orElse(null);
                if (!validReceipt(record.request(), receipt)) {
                    lifecycle.markAmbiguous("terminal transaction receipt is invalid");
                    return ProviderResult.recoveryRequired("terminal transaction receipt is invalid");
                }
                return ProviderResult.confirmed(receipt);
            }
            if (record.resultStatus() != ProviderResultStatus.REJECTED || record.receipt().isPresent()) {
                lifecycle.markAmbiguous("terminal transaction outcome is inconsistent");
                return ProviderResult.recoveryRequired("terminal transaction outcome is inconsistent");
            }
            return ProviderResult.rejected(ProviderError.DUPLICATE_REQUEST, record.diagnostic());
        }
        if (record.state() == EconomyTransactionState.UNCERTAIN) {
            return ProviderResult.recoveryRequired("transaction requires operator recovery");
        }
        return ProviderResult.recoveryRequired("transaction is already pending recovery");
    }

    private static boolean custodyTerminalStateMatches(CustodyState actual, CustodyState expected) {
        return switch (expected) {
            case HELD -> actual == CustodyState.HELD || actual == CustodyState.DELIVERED
                    || actual == CustodyState.CLAIMED;
            case DELIVERED -> actual == CustodyState.DELIVERED || actual == CustodyState.CLAIMED;
            case CLAIMED -> actual == CustodyState.CLAIMED;
            case RELEASED -> actual == CustodyState.RELEASED;
        };
    }

    private static boolean custodyMatches(CustodyRecord record, UUID owner, String itemKey,
                                          long quantity, String contentHash) {
        return record.owner().equals(owner) && record.itemKey().equals(itemKey)
                && record.quantity() == quantity && record.contentHash().equals(contentHash);
    }

    private ProviderResult<MutationReceipt> ambiguous(EconomyJournalRecord pending, String diagnostic) {
        return markUncertainOrFreeze(pending, diagnostic, true);
    }

    private ProviderResult<MutationReceipt> markUncertainOrFreeze(EconomyJournalRecord pending, String diagnostic) {
        return markUncertainOrFreeze(pending, diagnostic, false);
    }

    private ProviderResult<MutationReceipt> markUncertainOrFreeze(EconomyJournalRecord pending, String diagnostic,
                                                                  boolean ambiguousResult) {
        try {
            replace(pending, EconomyTransactionState.UNCERTAIN, Optional.empty(),
                    ProviderResultStatus.AMBIGUOUS, diagnostic);
        } catch (RuntimeException exception) {
            return journalFailure("transaction outcome could not be persisted");
        }
        lifecycle.markAmbiguous(diagnostic);
        return ambiguousResult ? ProviderResult.ambiguous(diagnostic) : ProviderResult.recoveryRequired(diagnostic);
    }

    private ProviderResult<MutationReceipt> journalFailure(String diagnostic) {
        lifecycle.markAmbiguous(diagnostic);
        return ProviderResult.recoveryRequired(diagnostic);
    }

    private boolean supports(EconomyCapability capability) {
        try {
            ProviderCapabilities capabilities = provider.capabilities();
            return capabilities != null && capabilities.supports(capability);
        } catch (RuntimeException exception) {
            lifecycle.markFailed("provider capability lookup failed");
            return false;
        }
    }

    private static void publishConfirmedBalanceChange(MutationRequest request, MutationReceipt receipt) {
        if (receipt == null || receipt.resultingBalanceMinorUnits().isEmpty()) {
            return;
        }
        long delta = switch (request.kind()) {
            case DEPOSIT, TRANSFER_CREDIT, REFUND -> request.amountMinorUnits();
            case WITHDRAW, TRANSFER_DEBIT, FEE, COMPENSATION -> -request.amountMinorUnits();
        };
        String reason = switch (request.kind()) {
            case DEPOSIT -> "DEPOSIT";
            case WITHDRAW -> "WITHDRAW";
            case TRANSFER_DEBIT, TRANSFER_CREDIT -> "TRANSFER";
            case FEE -> "FEE";
            case REFUND -> "REFUND";
            case COMPENSATION -> "COMPENSATION";
        };
        NeoForge.EVENT_BUS.post(new BalanceChangeEvent.Post(request.actor(), delta, reason,
                receipt.resultingBalanceMinorUnits().getAsLong()));
    }

    private boolean supportsAllMutationCapabilities() {
        return supports(EconomyCapability.PRECHECK) && supports(EconomyCapability.RECEIPT_LOOKUP)
                && supports(EconomyCapability.IDEMPOTENT_RETRY) && supports(EconomyCapability.WITHDRAW)
                && supports(EconomyCapability.DEPOSIT);
    }

    private void replace(EconomyJournalRecord source, EconomyTransactionState state,
                         Optional<MutationReceipt> receipt, ProviderResultStatus status, String diagnostic) {
        EconomyJournalRecord updated = new EconomyJournalRecord(source.request(), state, receipt, status, diagnostic,
                source.providerId().isBlank() ? provider.providerId() : source.providerId());
        journal.replace(updated);
        if (!journal.flush()) {
            throw new IllegalStateException("transaction journal flush failed");
        }
        receiptAudit.append(updated);
        if (!receiptAudit.flush()) {
            throw new IllegalStateException("receipt audit flush failed");
        }
        DebugDiagnostics.transaction(DebugModule.RECEIPT, "economy", "journal_replace", updated.request(), null,
                provider.capabilities(), null, updated.state().name(), updated.resultStatus().name(), "unknown", "unknown",
                "continue with the recorded state");
    }

    private void append(EconomyJournalRecord record) {
        journal.append(record);
        if (!journal.flush()) {
            throw new IllegalStateException("transaction journal flush failed");
        }
        receiptAudit.append(record);
        if (!receiptAudit.flush()) {
            throw new IllegalStateException("receipt audit flush failed");
        }
        DebugDiagnostics.transaction(DebugModule.RECEIPT, "economy", "journal_append", record.request(), null,
                provider.capabilities(), null, record.state().name(), record.resultStatus().name(), "unknown", "unknown",
                "continue with the recorded state");
    }

    private static boolean validReceipt(MutationRequest request, MutationReceipt receipt) {
        return receipt != null && request.requestId().equals(receipt.requestId())
                && request.kind() == receipt.kind() && request.amountMinorUnits() == receipt.amountMinorUnits()
                && receipt.externalOperationId() != null && !receipt.externalOperationId().isBlank();
    }

    private static boolean requestMatches(MutationRequest requested, MutationRequest persisted) {
        return requested.requestId().equals(persisted.requestId())
                && requested.actor().equals(persisted.actor())
                && requested.counterparty().equals(persisted.counterparty())
                && requested.amountMinorUnits() == persisted.amountMinorUnits()
                && requested.kind() == persisted.kind();
    }

    private boolean providerMatches(EconomyJournalRecord record) {
        return record.providerId().isBlank() || provider.providerId().equals(record.providerId());
    }

    private static <T> ProviderResult<T> copyFailure(ProviderResult<?> source) {
        return new ProviderResult<>(source.status(), source.error(), Optional.empty(), Optional.empty(), source.diagnostic());
    }

    private ProviderResult<MutationReceipt> executeBound(BoundEconomyOperationV1 operation,
                                                          MutationKind expectedKind) {
        Objects.requireNonNull(operation, "operation");
        synchronized (lock) {
            ProviderResult<BalanceSnapshot> bindingResult = validateBinding(operation);
            if (bindingResult != null) {
                return copyFailure(bindingResult);
            }
            return execute(operation.request(), expectedKind);
        }
    }

    private ProviderResult<BalanceSnapshot> validateBinding(BoundEconomyOperationV1 operation) {
        BoundEconomyOperationV1 registered = bindings.get(operation.request().requestId());
        if (registered == null || !sameBinding(registered, operation)) {
            return ProviderResult.rejected(ProviderError.BINDING_CHANGED,
                    "bound operation identity is not current");
        }
        PersistedAccountBindingV1 persisted = operation.persistedBinding();
        if (!provider.providerId().equals(persisted.providerId())
                || provider.compatibilityVersion() != persisted.providerApiVersion()
                || !provider.currency().singularName().equals(persisted.currencyId())
                || provider.currency().decimalPlaces() != persisted.currencyPrecision()) {
            return ProviderResult.rejected(ProviderError.BINDING_CHANGED,
                    "provider account binding changed");
        }
        if (!operation.runtimeProof().valid()) {
            return ProviderResult.rejected(ProviderError.CAPABILITY_MISSING,
                    "account binding proof is not available");
        }
        ProviderCapabilities declared;
        try {
            declared = provider.capabilities();
        } catch (RuntimeException exception) {
            lifecycle.markFailed("provider capability lookup failed for bound account");
            return ProviderResult.unavailable(ProviderError.CAPABILITY_MISSING,
                    "provider capability lookup failed");
        }
        ProviderCapabilities observed = operation.runtimeProof().accountObservedCapabilities();
        if (!supportsRequired(operation.requiredCapabilities(), declared, observed)) {
            return ProviderResult.unavailable(ProviderError.CAPABILITY_MISSING,
                    "bound account lacks required capabilities");
        }
        return null;
    }

    private static boolean sameBinding(BoundEconomyOperationV1 first, BoundEconomyOperationV1 second) {
        if (!requestMatches(first.request(), second.request())
                || !first.actorId().equals(second.actorId())
                || !first.requiredCapabilities().equals(second.requiredCapabilities())
                || !first.persistedBinding().equals(second.persistedBinding())) {
            return false;
        }
        RuntimeBindingProofV1 a = first.runtimeProof();
        RuntimeBindingProofV1 b = second.runtimeProof();
        return a.accountClass().equals(b.accountClass())
                && a.classLoaderIdentity().equals(b.classLoaderIdentity())
                && a.verifiedDescriptors().equals(b.verifiedDescriptors())
                && a.runtimeGeneration() == b.runtimeGeneration()
                && a.effectiveCapabilityProof().equals(b.effectiveCapabilityProof())
                && a.invalidationReason().equals(b.invalidationReason());
    }

    private static boolean supportsRequired(ProviderCapabilities required,
                                            ProviderCapabilities declared,
                                            ProviderCapabilities observed) {
        for (EconomyCapability capability : EconomyCapability.values()) {
            if (required.supports(capability)
                    && (!declared.supports(capability) || !observed.supports(capability))) {
                return false;
            }
        }
        return true;
    }

    private <T> ProviderResult<T> unavailableForLifecycle() {
        EconomyLifecycleSnapshot state = lifecycle.snapshot();
        if (state.lifecycle() == ProviderLifecycle.RECOVERING || state.lifecycle() == ProviderLifecycle.FROZEN) {
            return ProviderResult.recoveryRequired(state.diagnostic());
        }
        return ProviderResult.unavailable(ProviderError.NOT_READY,
                state.diagnostic().isBlank() ? "provider is not ready" : state.diagnostic());
    }
}
