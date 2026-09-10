package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.AccountBindingCodecV1;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filesystem backed local receipt audit journal. */
public final class FileEconomyReceiptAuditJournal implements EconomyReceiptAuditJournal {
    public static final String DIRECTORY_NAME = "receipts";
    private static final int CURRENT_VERSION = 1;
    private static final int MAX_RECORDS = 10_000;
    private static final int MAX_FILE_BYTES = 128 * 1024;
    private static final String CLEAN_MARKER = ".clean";
    private static final Pattern ENTRY_NAME = Pattern.compile("receipt-(\\d{1,20})\\.properties");
    private static final Set<String> ENTRY_KEYS = Set.of(
            "version", "request", "actor", "counterparty", "amount", "kind", "state", "status",
            "provider", "diagnostic", "binding", "receiptRequest", "receiptKind", "receiptAmount", "operation",
            "resultingBalance", "checksum");

    private final Path directory;
    private final List<EconomyJournalRecord> records = new ArrayList<>();
    private boolean newStorage;
    private boolean integrityValid = true;
    private boolean cleanMarkerValid = true;
    private long nextSequence;

    public FileEconomyReceiptAuditJournal(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.newStorage = !Files.exists(this.directory);
        load();
    }

    public Path directory() {
        return directory;
    }

    public boolean newStorage() {
        return newStorage;
    }

    public synchronized boolean migrateFrom(EconomyTransactionJournal journal) {
        if (!newStorage || !records.isEmpty()) {
            return integrityValid;
        }
        if (!journal.integrityValid()) {
            integrityValid = false;
            cleanMarkerValid = false;
            return false;
        }
        try {
            for (EconomyJournalRecord record : journal.snapshot()) {
                append(record);
            }
            newStorage = false;
            return flush();
        } catch (RuntimeException exception) {
            integrityValid = false;
            cleanMarkerValid = false;
            return false;
        }
    }

    @Override
    public synchronized void append(EconomyJournalRecord record) {
        if (!integrityValid || !ReceiptAuditValidation.valid(record)) {
            integrityValid = false;
            throw new IllegalStateException("receipt audit record is invalid");
        }
        EconomyJournalRecord previous = latest(record.request().requestId()).orElse(null);
        if (previous != null && !ReceiptAuditValidation.allowed(previous, record)) {
            integrityValid = false;
            throw new IllegalStateException("receipt audit transition is contradictory");
        }
        try {
            Files.createDirectories(directory);
            if (cleanMarkerValid) {
                writeCleanMarker(false);
            }
            long sequence = nextSequence++;
            writeEntry(sequence, record);
            records.add(record);
            cleanMarkerValid = false;
        } catch (IOException exception) {
            integrityValid = false;
            throw new IllegalStateException("receipt audit record could not be durably written", exception);
        }
    }

    @Override
    public synchronized List<EconomyJournalRecord> snapshot() {
        return List.copyOf(records);
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
    public synchronized boolean flush() {
        if (!integrityValid || !Files.exists(directory)) {
            return integrityValid;
        }
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    force(path);
                }
            }
            forceDirectory();
            return true;
        } catch (IOException exception) {
            integrityValid = false;
            return false;
        }
    }

    @Override
    public synchronized void markUnclean() {
        try {
            Files.createDirectories(directory);
            writeCleanMarker(false);
            cleanMarkerValid = false;
        } catch (IOException exception) {
            integrityValid = false;
            cleanMarkerValid = false;
        }
    }

    @Override
    public synchronized void markCleanMarker() {
        if (!integrityValid) {
            cleanMarkerValid = false;
            return;
        }
        try {
            Files.createDirectories(directory);
            writeCleanMarker(true);
            cleanMarkerValid = true;
        } catch (IOException exception) {
            integrityValid = false;
            cleanMarkerValid = false;
        }
    }

    private void load() {
        if (!Files.exists(directory)) {
            return;
        }
        if (!Files.isDirectory(directory)) {
            integrityValid = false;
            cleanMarkerValid = false;
            return;
        }
        try {
            List<Path> entries = new ArrayList<>();
            boolean markerPresent = false;
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    if (path.getFileName().toString().equals(CLEAN_MARKER)) {
                        markerPresent = true;
                    } else {
                        entries.add(path);
                    }
                }
            }
            entries.sort(Comparator.comparingLong(FileEconomyReceiptAuditJournal::sequenceOf));
            if (entries.size() > MAX_RECORDS) {
                integrityValid = false;
                cleanMarkerValid = false;
                return;
            }
            Set<Long> sequences = new HashSet<>();
            for (Path path : entries) {
                Matcher matcher = ENTRY_NAME.matcher(path.getFileName().toString());
                if (!Files.isRegularFile(path) || !matcher.matches()) {
                    integrityValid = false;
                    continue;
                }
                long sequence = Long.parseLong(matcher.group(1));
                if (!sequences.add(sequence)) {
                    integrityValid = false;
                    continue;
                }
                EconomyJournalRecord record = readEntry(path);
                EconomyJournalRecord previous = latest(record.request().requestId()).orElse(null);
                if (previous != null && !ReceiptAuditValidation.allowed(previous, record)) {
                    integrityValid = false;
                }
                records.add(record);
                nextSequence = Math.max(nextSequence, Math.addExact(sequence, 1L));
            }
            if (markerPresent) {
                cleanMarkerValid = readCleanMarker();
            } else if (!entries.isEmpty()) {
                cleanMarkerValid = false;
            }
        } catch (Exception exception) {
            integrityValid = false;
            cleanMarkerValid = false;
        }
        if (!integrityValid) {
            records.clear();
            cleanMarkerValid = false;
        }
    }

    private EconomyJournalRecord readEntry(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("receipt audit record size is invalid");
        }
        Properties properties = new Properties();
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            properties.load(input);
        }
        if (!ENTRY_KEYS.containsAll(properties.stringPropertyNames())
                || !properties.stringPropertyNames().containsAll(Set.of(
                "version", "request", "actor", "amount", "kind", "state", "status", "provider", "diagnostic", "checksum"))) {
            throw new IOException("receipt audit record fields are invalid");
        }
        int version = Integer.parseInt(properties.getProperty("version"));
        if (version < 1 || version > CURRENT_VERSION) {
            throw new IOException("receipt audit record version is unsupported");
        }
        RequestId requestId = new RequestId(UUID.fromString(properties.getProperty("request")));
        MutationRequest request = new MutationRequest(requestId, UUID.fromString(properties.getProperty("actor")),
                optionalUuid(properties.getProperty("counterparty")), Long.parseLong(properties.getProperty("amount")),
                MutationKind.valueOf(properties.getProperty("kind")));
        EconomyTransactionState state = EconomyTransactionState.valueOf(properties.getProperty("state"));
        ProviderResultStatus status = ProviderResultStatus.valueOf(properties.getProperty("status"));
        String provider = properties.getProperty("provider");
        String diagnostic = properties.getProperty("diagnostic");
        java.util.Optional<PersistedAccountBindingV1> binding = properties.containsKey("binding")
                ? java.util.Optional.of(AccountBindingCodecV1.decode(properties.getProperty("binding")))
                : java.util.Optional.empty();
        MutationReceipt receipt = null;
        boolean anyReceipt = properties.stringPropertyNames().stream().anyMatch(key -> key.startsWith("receipt") || key.equals("operation"));
        if (anyReceipt) {
            if (!properties.stringPropertyNames().containsAll(Set.of("receiptRequest", "receiptKind", "receiptAmount", "operation"))) {
                throw new IOException("receipt audit provider receipt is incomplete");
            }
            receipt = new MutationReceipt(new RequestId(UUID.fromString(properties.getProperty("receiptRequest"))),
                    MutationKind.valueOf(properties.getProperty("receiptKind")),
                    Long.parseLong(properties.getProperty("receiptAmount")), properties.getProperty("operation"),
                    properties.containsKey("resultingBalance")
                            ? java.util.OptionalLong.of(Long.parseLong(properties.getProperty("resultingBalance")))
                            : java.util.OptionalLong.empty());
        }
        EconomyJournalRecord record = new EconomyJournalRecord(request, state,
                java.util.Optional.ofNullable(receipt), status, diagnostic, provider, binding);
        String expectedChecksum = version < CURRENT_VERSION && binding.isEmpty()
                ? legacyChecksum(record) : checksum(record);
        if (!properties.getProperty("checksum").equals(expectedChecksum) || !ReceiptAuditValidation.valid(record)) {
            throw new IOException("receipt audit checksum is invalid");
        }
        return record;
    }

    private void writeEntry(long sequence, EconomyJournalRecord record) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(CURRENT_VERSION));
        MutationRequest request = record.request();
        properties.setProperty("request", request.requestId().value().toString());
        properties.setProperty("actor", request.actor().toString());
        request.counterparty().ifPresent(value -> properties.setProperty("counterparty", value.toString()));
        properties.setProperty("amount", Long.toString(request.amountMinorUnits()));
        properties.setProperty("kind", request.kind().name());
        properties.setProperty("state", record.state().name());
        properties.setProperty("status", record.resultStatus().name());
        properties.setProperty("provider", record.providerId());
        properties.setProperty("diagnostic", record.diagnostic());
        record.binding().ifPresent(value -> properties.setProperty("binding", AccountBindingCodecV1.encode(value)));
        record.receipt().ifPresent(receipt -> {
            properties.setProperty("receiptRequest", receipt.requestId().value().toString());
            properties.setProperty("receiptKind", receipt.kind().name());
            properties.setProperty("receiptAmount", Long.toString(receipt.amountMinorUnits()));
            properties.setProperty("operation", receipt.externalOperationId());
            if (receipt.resultingBalanceMinorUnits().isPresent()) {
                properties.setProperty("resultingBalance", Long.toString(receipt.resultingBalanceMinorUnits().getAsLong()));
            }
        });
        properties.setProperty("checksum", checksum(record));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        writeAtomically(directory.resolve(String.format("receipt-%020d.properties", sequence)), output.toByteArray());
    }

    private boolean readCleanMarker() throws IOException {
        byte[] bytes = Files.readAllBytes(directory.resolve(CLEAN_MARKER));
        if (bytes.length == 0 || bytes.length > 4096) {
            throw new IOException("receipt audit clean marker size is invalid");
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        if (!properties.stringPropertyNames().equals(Set.of("version", "state", "checksum"))) {
            throw new IOException("receipt audit clean marker fields are invalid");
        }
        String state = properties.getProperty("state");
        String expected = EconomyRecordChecksum.sha256(properties.getProperty("version") + "|" + state);
        if (!Integer.toString(CURRENT_VERSION).equals(properties.getProperty("version"))
                || !expected.equals(properties.getProperty("checksum"))) {
            throw new IOException("receipt audit clean marker checksum is invalid");
        }
        return "clean".equals(state);
    }

    private void writeCleanMarker(boolean clean) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(CURRENT_VERSION));
        properties.setProperty("state", clean ? "clean" : "unclean");
        properties.setProperty("checksum", EconomyRecordChecksum.sha256(CURRENT_VERSION + "|" + (clean ? "clean" : "unclean")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        writeAtomically(directory.resolve(CLEAN_MARKER), output.toByteArray());
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            force(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static java.util.Optional<UUID> optionalUuid(String value) {
        return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(UUID.fromString(value));
    }

    private static long sequenceOf(Path path) {
        Matcher matcher = ENTRY_NAME.matcher(path.getFileName().toString());
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }

    private static String checksum(EconomyJournalRecord record) {
        return EconomyRecordChecksum.sha256(ReceiptAuditValidation.canonical(record));
    }

    private static String legacyChecksum(EconomyJournalRecord record) {
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
        record.receipt().ifPresent(receipt -> canonical.append(receipt.requestId().value()).append('|')
                .append(receipt.kind()).append('|')
                .append(receipt.amountMinorUnits()).append('|')
                .append(receipt.externalOperationId()).append('|')
                .append(receipt.resultingBalanceMinorUnits().isPresent()
                        ? receipt.resultingBalanceMinorUnits().getAsLong() : ""));
        return EconomyRecordChecksum.sha256(canonical.toString());
    }
}
