package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import com.enviouse.futureshopsp.api.economy.EconomyApi;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyJournalSavedDataTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Test
    void roundTripsChecksummedPendingAndConfirmedRecords() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), PLAYER, 12L, MutationKind.WITHDRAW);
        data.append(new EconomyJournalRecord(request, EconomyTransactionState.EXTERNAL_PENDING,
                Optional.empty(), ProviderResultStatus.UNAVAILABLE, "pending"));
        MutationReceipt receipt = new MutationReceipt(request.requestId(), request.kind(), request.amountMinorUnits(),
                "external-1", OptionalLong.of(88L));
        data.replace(new EconomyJournalRecord(request, EconomyTransactionState.RESOLVED,
                Optional.of(receipt), ProviderResultStatus.CONFIRMED, "", "fixture"));

        CompoundTag saved = data.save(new CompoundTag(), null);
        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertTrue(loaded.integrityValid());
        assertEquals(EconomyTransactionState.RESOLVED,
                loaded.find(request.requestId()).orElseThrow().state());
        assertEquals(receipt, loaded.find(request.requestId()).orElseThrow().receipt().orElseThrow());
        assertEquals("fixture", loaded.find(request.requestId()).orElseThrow().providerId());
    }

    @Test
    void roundTripsAnImmutableAccountBindingAlongsideTheRequest() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        RequestId requestId = RequestId.random();
        MutationRequest request = MutationRequest.forPlayer(requestId, PLAYER, 12L, MutationKind.WITHDRAW);
        PersistedAccountBindingV1 binding = new PersistedAccountBindingV1(
                EconomyApi.PIXELMON_PROVIDER_ID, EconomyApi.COMPATIBILITY_VERSION, "pixelmon.finaleconomy.bridge",
                "pixelmon-exact-account-v1", "FEBankAccount",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Optional.empty(), "finaleconomy:1.0.9", PLAYER, "PokéDollar", 0, "BankAccountProxy", 0L,
                requestId, requestId,
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1);
        data.append(new EconomyJournalRecord(request, EconomyTransactionState.EXTERNAL_PENDING,
                Optional.empty(), ProviderResultStatus.UNAVAILABLE, "pending", "pixelmon", Optional.of(binding)));

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(data.save(new CompoundTag(), null), null);

        assertTrue(loaded.integrityValid());
        assertEquals(binding, loaded.find(requestId).orElseThrow().binding().orElseThrow());
    }

    @Test
    void checksumTamperingInvalidatesOnlyTheLoadedJournal() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), PLAYER, 12L, MutationKind.WITHDRAW);
        data.append(new EconomyJournalRecord(request, EconomyTransactionState.EXTERNAL_PENDING,
                Optional.empty(), ProviderResultStatus.UNAVAILABLE, "pending"));
        CompoundTag saved = data.save(new CompoundTag(), null);
        saved.getList("records", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 13L);

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void oneMalformedRecordPreventsPartialJournalRecovery() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        MutationRequest first = MutationRequest.forPlayer(RequestId.random(), PLAYER, 12L, MutationKind.WITHDRAW);
        MutationRequest second = MutationRequest.forPlayer(RequestId.random(), PLAYER, 8L, MutationKind.DEPOSIT);
        data.append(new EconomyJournalRecord(first, EconomyTransactionState.RESOLVED,
                Optional.empty(), ProviderResultStatus.REJECTED, "rejected"));
        data.append(new EconomyJournalRecord(second, EconomyTransactionState.EXTERNAL_PENDING,
                Optional.empty(), ProviderResultStatus.UNAVAILABLE, "pending"));

        CompoundTag saved = data.save(new CompoundTag(), null);
        saved.getList("records", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(1).putLong("amount", 9L);

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void mismatchedReceiptCannotBeLoadedAsConfirmedEvidence() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), PLAYER, 12L, MutationKind.WITHDRAW);
        MutationReceipt mismatched = new MutationReceipt(request.requestId(), request.kind(), 13L,
                "external-mismatch", OptionalLong.empty());
        data.append(new EconomyJournalRecord(request, EconomyTransactionState.RESOLVED,
                Optional.of(mismatched), ProviderResultStatus.CONFIRMED, ""));

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(data.save(new CompoundTag(), null), null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void confirmedStatusWithoutReceiptIsReadOnlyAndNeverLoadedAsSuccess() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), PLAYER, 12L, MutationKind.WITHDRAW);
        data.append(new EconomyJournalRecord(request, EconomyTransactionState.RESOLVED,
                Optional.empty(), ProviderResultStatus.CONFIRMED, ""));

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(data.save(new CompoundTag(), null), null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void newerSchemaIsReadOnlyAndNeverInterpretedAsCompleted() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("schemaVersion", 99);

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void wrongRecordsTagTypeIsReadOnlyAndNeverInterpretedAsEmptySuccess() {
        CompoundTag saved = new CompoundTag();
        saved.putString("records", "not a list");

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void currentSchemaMissingRecordsIsReadOnlyAndNeverInterpretedAsEmptySuccess() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("schemaVersion", 1);

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void oversizedJournalIsReadOnlyAndNeverLoaded() {
        CompoundTag saved = new CompoundTag();
        net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();
        for (int index = 0; index < 10_001; index++) {
            entries.add(new CompoundTag());
        }
        saved.put("records", entries);

        EconomyJournalSavedData loaded = EconomyJournalSavedData.load(saved, null);

        assertFalse(loaded.integrityValid());
        assertTrue(loaded.snapshot().isEmpty());
    }

    @Test
    void cleanMarkerIsWrittenOnlyAfterTheJournalIsFlushed() {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        data.markUnclean();
        assertFalse(data.cleanMarkerValid());

        CompoundTag dirty = data.save(new CompoundTag(), null);
        assertFalse(dirty.getBoolean("cleanMarker"));

        data.markCleanMarker();
        CompoundTag clean = data.save(new CompoundTag(), null);
        assertTrue(clean.getBoolean("cleanMarker"));
    }
}
