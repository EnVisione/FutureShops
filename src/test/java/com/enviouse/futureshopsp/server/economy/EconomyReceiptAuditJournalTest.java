package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.EconomyApi;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyReceiptAuditJournalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsTransitionsAndCleanMarker() throws Exception {
        FileEconomyReceiptAuditJournal journal = new FileEconomyReceiptAuditJournal(temporaryDirectory.resolve("receipts"));
        MutationRequest request = request();
        journal.append(record(request, EconomyTransactionState.PREPARED, ProviderResultStatus.REJECTED));
        journal.append(record(request, EconomyTransactionState.EXTERNAL_PENDING, ProviderResultStatus.UNAVAILABLE));
        assertTrue(journal.hasIncompleteRecords());
        journal.append(record(request, EconomyTransactionState.RESOLVED, ProviderResultStatus.REJECTED));

        assertFalse(journal.cleanMarkerValid());
        assertFalse(journal.hasIncompleteRecords());
        journal.markCleanMarker();

        FileEconomyReceiptAuditJournal reloaded = new FileEconomyReceiptAuditJournal(journal.directory());
        assertTrue(reloaded.integrityValid());
        assertTrue(reloaded.cleanMarkerValid());
        assertFalse(reloaded.hasIncompleteRecords());
        assertEquals(3, reloaded.snapshot().size());
        assertEquals(EconomyTransactionState.RESOLVED,
                reloaded.latest(request.requestId()).orElseThrow().state());

        Path firstEntry = Files.list(journal.directory())
                .filter(path -> path.getFileName().toString().endsWith(".properties"))
                .findFirst().orElseThrow();
        String contents = Files.readString(firstEntry);
        assertFalse(contents.contains("balance"));
    }

    @Test
    void checksumCorruptionBlocksRecovery() throws Exception {
        FileEconomyReceiptAuditJournal journal = new FileEconomyReceiptAuditJournal(temporaryDirectory.resolve("receipts"));
        journal.append(record(request(), EconomyTransactionState.PREPARED, ProviderResultStatus.REJECTED));
        journal.markCleanMarker();
        Path entry = Files.list(journal.directory())
                .filter(path -> path.getFileName().toString().endsWith(".properties"))
                .findFirst().orElseThrow();
        Files.writeString(entry, "version=1\n", StandardCharsets.UTF_8);

        FileEconomyReceiptAuditJournal reloaded = new FileEconomyReceiptAuditJournal(journal.directory());
        assertFalse(reloaded.integrityValid());
        assertFalse(reloaded.cleanMarkerValid());
    }

    @Test
    void preservesProviderReceiptFactsWithoutMakingThemASecondLedger() {
        FileEconomyReceiptAuditJournal journal = new FileEconomyReceiptAuditJournal(temporaryDirectory.resolve("receipts"));
        MutationRequest request = request();
        journal.append(record(request, EconomyTransactionState.PREPARED, ProviderResultStatus.REJECTED));
        journal.append(record(request, EconomyTransactionState.EXTERNAL_PENDING, ProviderResultStatus.UNAVAILABLE));
        MutationReceipt receipt = new MutationReceipt(request.requestId(), request.kind(), request.amountMinorUnits(),
                "provider-operation", OptionalLong.of(75L));
        EconomyJournalRecord confirmed = new EconomyJournalRecord(request, EconomyTransactionState.EXTERNAL_CONFIRMED,
                java.util.Optional.of(receipt), ProviderResultStatus.CONFIRMED, "", "internal");
        journal.append(confirmed);

        FileEconomyReceiptAuditJournal reloaded = new FileEconomyReceiptAuditJournal(journal.directory());
        assertTrue(reloaded.integrityValid());
        assertEquals(receipt, reloaded.latest(request.requestId()).orElseThrow().receipt().orElseThrow());
    }

    @Test
    void roundTripsThePersistedAccountBindingWithTheAuditRecord() {
        FileEconomyReceiptAuditJournal journal = new FileEconomyReceiptAuditJournal(
                temporaryDirectory.resolve("bound-receipts"));
        MutationRequest request = request();
        PersistedAccountBindingV1 binding = new PersistedAccountBindingV1(
                EconomyApi.PIXELMON_PROVIDER_ID, EconomyApi.COMPATIBILITY_VERSION,
                "pixelmon.finaleconomy.bridge", "pixelmon-exact-account-v1", "FEBankAccount",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", Optional.empty(),
                "finaleconomy:1.0.9", request.actor(), "PokéDollar", 0, "BankAccountProxy", 0L,
                request.requestId(), request.requestId(),
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1);
        EconomyJournalRecord pending = new EconomyJournalRecord(request, EconomyTransactionState.EXTERNAL_PENDING,
                Optional.empty(), ProviderResultStatus.UNAVAILABLE, "pending", "pixelmon", Optional.of(binding));
        journal.append(pending);

        FileEconomyReceiptAuditJournal reloaded = new FileEconomyReceiptAuditJournal(journal.directory());

        assertTrue(reloaded.integrityValid());
        assertEquals(binding, reloaded.latest(request.requestId()).orElseThrow().binding().orElseThrow());
    }

    @Test
    void unknownFilesBlockRecovery() throws Exception {
        Path directory = temporaryDirectory.resolve("receipts");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("unknown-record.properties"), "version=1\n", StandardCharsets.UTF_8);

        FileEconomyReceiptAuditJournal journal = new FileEconomyReceiptAuditJournal(directory);
        assertFalse(journal.integrityValid());
        assertFalse(journal.cleanMarkerValid());
    }

    @Test
    void contradictoryTransitionIsRejected() {
        InMemoryEconomyReceiptAuditJournal journal = new InMemoryEconomyReceiptAuditJournal();
        MutationRequest request = request();
        journal.append(record(request, EconomyTransactionState.PREPARED, ProviderResultStatus.REJECTED));

        assertThrows(IllegalStateException.class,
                () -> journal.append(record(request, EconomyTransactionState.RESOLVED, ProviderResultStatus.REJECTED)));
        assertFalse(journal.integrityValid());
    }

    @Test
    void journalAndAuditMustHaveTheSameLatestTransition() {
        InMemoryEconomyTransactionJournal transactionJournal = new InMemoryEconomyTransactionJournal();
        InMemoryEconomyReceiptAuditJournal auditJournal = new InMemoryEconomyReceiptAuditJournal();
        MutationRequest request = request();
        EconomyJournalRecord prepared = record(request, EconomyTransactionState.PREPARED,
                ProviderResultStatus.REJECTED);
        transactionJournal.append(prepared);
        auditJournal.append(prepared);
        assertTrue(auditJournal.matches(transactionJournal));

        EconomyJournalRecord pending = record(request, EconomyTransactionState.EXTERNAL_PENDING,
                ProviderResultStatus.UNAVAILABLE);
        auditJournal.append(pending);
        assertFalse(auditJournal.matches(transactionJournal));
    }

    @Test
    void migratesExistingJournalRowsIntoNewStorage() {
        InMemoryEconomyTransactionJournal transactionJournal = new InMemoryEconomyTransactionJournal();
        MutationRequest request = request();
        EconomyJournalRecord resolved = record(request, EconomyTransactionState.RESOLVED,
                ProviderResultStatus.REJECTED);
        transactionJournal.append(resolved);

        FileEconomyReceiptAuditJournal auditJournal = new FileEconomyReceiptAuditJournal(
                temporaryDirectory.resolve("new-receipts"));
        assertTrue(auditJournal.newStorage());
        assertTrue(auditJournal.migrateFrom(transactionJournal));
        assertFalse(auditJournal.newStorage());
        assertFalse(auditJournal.cleanMarkerValid());
        assertTrue(auditJournal.matches(transactionJournal));
    }

    private static MutationRequest request() {
        return MutationRequest.forPlayer(new RequestId(UUID.randomUUID()), UUID.randomUUID(), 100L,
                MutationKind.WITHDRAW);
    }

    private static EconomyJournalRecord record(MutationRequest request, EconomyTransactionState state,
                                               ProviderResultStatus status) {
        return new EconomyJournalRecord(request, state, Optional.empty(), status, "", "internal");
    }
}
