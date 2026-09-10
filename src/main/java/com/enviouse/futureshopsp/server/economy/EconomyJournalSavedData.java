package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.AccountBindingCodecV1;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.server.SavedDataMigrations;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Versioned and checksummed SavedData journal for transaction intent and outcomes. */
public final class EconomyJournalSavedData extends SavedData implements EconomyTransactionJournal {
    public static final String DATA_NAME = "futureshops_economy_journal";
    private static final int CURRENT_VERSION = 1;
    private static final int MAX_RECORDS = 10_000;
    private final Map<RequestId, EconomyJournalRecord> records = new LinkedHashMap<>();
    private boolean integrityValid = true;
    private boolean cleanMarkerValid = true;

    public static EconomyJournalSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EconomyJournalSavedData::new, EconomyJournalSavedData::load, null), DATA_NAME);
    }

    public static EconomyJournalSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        EconomyJournalSavedData data = new EconomyJournalSavedData();
        int version = SavedDataMigrations.readVersion(tag);
        if (version > CURRENT_VERSION) {
            data.integrityValid = false;
            return data;
        }
        SavedDataMigrations.needsMigration(DATA_NAME, version, CURRENT_VERSION);
        if (tag.contains("cleanMarker", Tag.TAG_BYTE)) {
            data.cleanMarkerValid = tag.getBoolean("cleanMarker");
        }
        Tag rawEntries = tag.get("records");
        if (version < 0 || (version == CURRENT_VERSION && rawEntries == null)) {
            data.integrityValid = false;
            return data;
        }
        if (rawEntries != null && !(rawEntries instanceof ListTag)) {
            data.integrityValid = false;
            return data;
        }
        ListTag entries = rawEntries instanceof ListTag list ? list : new ListTag();
        if (entries.size() > MAX_RECORDS) {
            data.integrityValid = false;
            return data;
        }
        Map<RequestId, EconomyJournalRecord> loaded = new LinkedHashMap<>();
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entry)) {
                data.integrityValid = false;
                continue;
            }
            try {
                EconomyJournalRecord record = readEntry(entry, version);
                if (loaded.put(record.request().requestId(), record) != null) {
                    data.integrityValid = false;
                }
            } catch (RuntimeException exception) {
                data.integrityValid = false;
            }
        }
        if (data.integrityValid) {
            data.records.putAll(loaded);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        SavedDataMigrations.writeVersion(tag, CURRENT_VERSION);
        ListTag entries = new ListTag();
        for (EconomyJournalRecord record : records.values()) {
            entries.add(writeEntry(record));
        }
        tag.put("records", entries);
        tag.putBoolean("cleanMarker", cleanMarkerValid);
        return tag;
    }

    @Override
    public synchronized Optional<EconomyJournalRecord> find(RequestId requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    @Override
    public synchronized void append(EconomyJournalRecord record) {
        if (records.containsKey(record.request().requestId())) {
            throw new IllegalStateException("transaction request already exists");
        }
        if (records.size() >= MAX_RECORDS) {
            throw new IllegalStateException("transaction journal record limit reached");
        }
        records.put(record.request().requestId(), record);
        setDirty();
    }

    @Override
    public synchronized void replace(EconomyJournalRecord record) {
        if (!records.containsKey(record.request().requestId())) {
            throw new IllegalStateException("transaction request does not exist");
        }
        records.put(record.request().requestId(), record);
        setDirty();
    }

    @Override
    public synchronized List<EconomyJournalRecord> snapshot() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    @Override
    public synchronized boolean integrityValid() {
        return integrityValid;
    }

    @Override
    public synchronized boolean cleanMarkerValid() {
        return cleanMarkerValid;
    }

    @Override
    public synchronized void markUnclean() {
        cleanMarkerValid = false;
        setDirty();
    }

    @Override
    public synchronized void markCleanMarker() {
        cleanMarkerValid = true;
        setDirty();
    }

    private static EconomyJournalRecord readEntry(CompoundTag entry, int version) {
        if (!entry.hasUUID("request") || !entry.hasUUID("actor")
                || !entry.contains("amount", Tag.TAG_LONG) || !entry.contains("kind", Tag.TAG_STRING)
                || !entry.contains("state", Tag.TAG_STRING) || !entry.contains("status", Tag.TAG_STRING)
                || !entry.contains("checksum", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("journal record is incomplete");
        }
        RequestId requestId = new RequestId(entry.getUUID("request"));
        boolean hasProvider = entry.contains("provider", Tag.TAG_STRING);
        String providerId = hasProvider ? entry.getString("provider") : "legacy-unbound";
        Optional<UUID> counterparty = entry.hasUUID("counterparty")
                ? Optional.of(entry.getUUID("counterparty")) : Optional.empty();
        MutationRequest request = new MutationRequest(requestId, entry.getUUID("actor"), counterparty,
                entry.getLong("amount"), MutationKind.valueOf(entry.getString("kind")));
        EconomyTransactionState state = EconomyTransactionState.valueOf(entry.getString("state"));
        ProviderResultStatus status = ProviderResultStatus.valueOf(entry.getString("status"));
        String diagnostic = entry.getString("diagnostic");
        Optional<PersistedAccountBindingV1> binding = Optional.empty();
        if (entry.contains("binding", Tag.TAG_STRING)) {
            binding = Optional.of(AccountBindingCodecV1.decode(entry.getString("binding")));
        }
        Optional<MutationReceipt> receipt = Optional.empty();
        if (entry.hasUUID("receiptRequest") && entry.contains("receiptAmount", Tag.TAG_LONG)
                && entry.contains("receiptKind", Tag.TAG_STRING)
                && entry.contains("operation", Tag.TAG_STRING)) {
            OptionalLong resulting = entry.contains("resultingBalance", Tag.TAG_LONG)
                    ? OptionalLong.of(entry.getLong("resultingBalance")) : OptionalLong.empty();
            receipt = Optional.of(new MutationReceipt(new RequestId(entry.getUUID("receiptRequest")),
                    MutationKind.valueOf(entry.getString("receiptKind")), entry.getLong("receiptAmount"),
                    entry.getString("operation"), resulting));
        }
        EconomyJournalRecord record = new EconomyJournalRecord(request, state, receipt, status, diagnostic,
                providerId, binding);
        String expectedChecksum = version < CURRENT_VERSION && binding.isEmpty()
                ? hasProvider
                ? legacyProviderChecksum(request, state, receipt, status, diagnostic, providerId)
                : legacyChecksum(request, state, receipt, status, diagnostic)
                : checksum(record);
        if (!entry.getString("checksum").equals(expectedChecksum)) {
            throw new IllegalArgumentException("journal record checksum mismatch");
        }
        if (receipt.isPresent() && !validReceipt(request, receipt.orElseThrow())) {
            throw new IllegalArgumentException("journal receipt does not match request");
        }
        if ((status == ProviderResultStatus.CONFIRMED) != receipt.isPresent()) {
            throw new IllegalArgumentException("journal confirmed status and receipt disagree");
        }
        return record;
    }

    private static boolean validReceipt(MutationRequest request, MutationReceipt receipt) {
        return request.requestId().equals(receipt.requestId())
                && request.kind() == receipt.kind()
                && request.amountMinorUnits() == receipt.amountMinorUnits()
                && receipt.externalOperationId() != null && !receipt.externalOperationId().isBlank();
    }

    private static CompoundTag writeEntry(EconomyJournalRecord record) {
        CompoundTag entry = new CompoundTag();
        MutationRequest request = record.request();
        entry.putUUID("request", request.requestId().value());
        entry.putUUID("actor", request.actor());
        request.counterparty().ifPresent(value -> entry.putUUID("counterparty", value));
        entry.putLong("amount", request.amountMinorUnits());
        entry.putString("kind", request.kind().name());
        entry.putString("state", record.state().name());
        entry.putString("status", record.resultStatus().name());
        entry.putString("diagnostic", record.diagnostic());
        if (!record.providerId().isBlank()) {
            entry.putString("provider", record.providerId());
        }
        record.binding().ifPresent(value -> entry.putString("binding", AccountBindingCodecV1.encode(value)));
        record.receipt().ifPresent(receipt -> {
            entry.putUUID("receiptRequest", receipt.requestId().value());
            entry.putString("receiptKind", receipt.kind().name());
            entry.putLong("receiptAmount", receipt.amountMinorUnits());
            entry.putString("operation", receipt.externalOperationId());
            if (receipt.resultingBalanceMinorUnits().isPresent()) {
                entry.putLong("resultingBalance", receipt.resultingBalanceMinorUnits().getAsLong());
            }
        });
        entry.putString("checksum", checksum(record));
        return entry;
    }

    private static String checksum(EconomyJournalRecord record) {
        return EconomyRecordChecksum.sha256(ReceiptAuditValidation.canonical(record));
    }

    private static String checksum(MutationRequest request, EconomyTransactionState state,
                                   Optional<MutationReceipt> receipt, ProviderResultStatus status,
                                   String diagnostic, String providerId) {
        StringBuilder canonical = new StringBuilder()
                .append(request.requestId().value()).append('|')
                .append(request.actor()).append('|')
                .append(request.counterparty().map(UUID::toString).orElse("" )).append('|')
                .append(request.amountMinorUnits()).append('|')
                .append(request.kind()).append('|').append(state).append('|').append(status).append('|')
                .append(providerId == null ? "" : providerId).append('|')
                .append(diagnostic == null ? "" : diagnostic).append('|');
        receipt.ifPresent(value -> canonical.append(value.requestId().value()).append('|')
                .append(value.kind()).append('|').append(value.amountMinorUnits()).append('|')
                .append(value.externalOperationId()).append('|')
                .append(value.resultingBalanceMinorUnits().isPresent()
                        ? Long.toString(value.resultingBalanceMinorUnits().getAsLong()) : ""));
        return digest(canonical.toString());
    }

    private static String legacyProviderChecksum(MutationRequest request, EconomyTransactionState state,
                                                 Optional<MutationReceipt> receipt, ProviderResultStatus status,
                                                 String diagnostic, String providerId) {
        return checksum(request, state, receipt, status, diagnostic, providerId);
    }

    private static String legacyChecksum(MutationRequest request, EconomyTransactionState state,
                                         Optional<MutationReceipt> receipt, ProviderResultStatus status,
                                         String diagnostic) {
        StringBuilder canonical = new StringBuilder()
                .append(request.requestId().value()).append('|')
                .append(request.actor()).append('|')
                .append(request.counterparty().map(UUID::toString).orElse("" )).append('|')
                .append(request.amountMinorUnits()).append('|')
                .append(request.kind()).append('|').append(state).append('|').append(status).append('|')
                .append(diagnostic == null ? "" : diagnostic).append('|');
        receipt.ifPresent(value -> canonical.append(value.requestId().value()).append('|')
                .append(value.kind()).append('|').append(value.amountMinorUnits()).append('|')
                .append(value.externalOperationId()).append('|')
                .append(value.resultingBalanceMinorUnits().isPresent()
                        ? Long.toString(value.resultingBalanceMinorUnits().getAsLong()) : ""));
        return digest(canonical.toString());
    }

    private static String digest(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha-256 is unavailable", exception);
        }
    }
}
