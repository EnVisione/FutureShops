package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.AccountBindingCodecV1;

import java.util.Optional;

/** Shared validation for local receipt audit records. */
final class ReceiptAuditValidation {
    private ReceiptAuditValidation() {
    }

    static boolean allowed(EconomyJournalRecord previous, EconomyJournalRecord next) {
        if (!previous.request().equals(next.request()) || !sameProvider(previous, next)) {
            return false;
        }
        if (previous.equals(next)) {
            return true;
        }
        return switch (previous.state()) {
            case PREPARED -> next.state() == EconomyTransactionState.EXTERNAL_PENDING;
            case EXTERNAL_PENDING -> next.state() == EconomyTransactionState.EXTERNAL_CONFIRMED
                    || next.state() == EconomyTransactionState.RESOLVED
                    || next.state() == EconomyTransactionState.UNCERTAIN;
            case EXTERNAL_CONFIRMED -> next.state() == EconomyTransactionState.RESOLVED
                    || next.state() == EconomyTransactionState.DELIVERED
                    || next.state() == EconomyTransactionState.CLAIMED;
            case DELIVERED -> next.state() == EconomyTransactionState.CLAIMED
                    || next.state() == EconomyTransactionState.RESOLVED;
            case CLAIMED -> next.state() == EconomyTransactionState.RESOLVED;
            case UNCERTAIN, RESOLVED -> false;
        };
    }

    static boolean valid(EconomyJournalRecord record) {
        if (record.providerId().isBlank()) {
            return false;
        }
        if (record.binding().isPresent()) {
            var binding = record.binding().orElseThrow();
            if (!record.providerId().equals(binding.providerId())
                    || !record.request().requestId().equals(binding.legRequestId())
                    || !record.request().requestId().equals(binding.rootRequestId())
                    || !record.request().actor().equals(binding.accountUuid())) {
                return false;
            }
        }
        Optional<MutationReceipt> receipt = record.receipt();
        if ((record.resultStatus() == ProviderResultStatus.CONFIRMED) != receipt.isPresent()) {
            return false;
        }
        return receipt.isEmpty() || validReceipt(record.request(), receipt.orElseThrow());
    }

    static String canonical(EconomyJournalRecord record) {
        MutationRequest request = record.request();
        StringBuilder canonical = new StringBuilder()
                .append(request.requestId().value()).append('|')
                .append(request.actor()).append('|')
                .append(request.counterparty().map(Object::toString).orElse("")).append('|')
                .append(request.amountMinorUnits()).append('|')
                .append(request.kind()).append('|')
                .append(record.state()).append('|')
                .append(record.resultStatus()).append('|')
                .append(record.providerId()).append('|')
                .append(record.diagnostic()).append('|');
        record.binding().ifPresent(binding -> canonical.append(AccountBindingCodecV1.encode(binding)).append('|'));
        record.receipt().ifPresent(receipt -> canonical.append(receipt.requestId().value()).append('|')
                .append(receipt.kind()).append('|')
                .append(receipt.amountMinorUnits()).append('|')
                .append(receipt.externalOperationId()).append('|')
                .append(receipt.resultingBalanceMinorUnits().isPresent()
                        ? receipt.resultingBalanceMinorUnits().getAsLong() : ""));
        return canonical.toString();
    }

    private static boolean sameProvider(EconomyJournalRecord first, EconomyJournalRecord second) {
        return first.providerId().equals(second.providerId());
    }

    private static boolean validReceipt(MutationRequest request, MutationReceipt receipt) {
        return request.requestId().equals(receipt.requestId())
                && request.kind() == receipt.kind()
                && request.amountMinorUnits() == receipt.amountMinorUnits()
                && receipt.externalOperationId() != null
                && !receipt.externalOperationId().isBlank();
    }
}
