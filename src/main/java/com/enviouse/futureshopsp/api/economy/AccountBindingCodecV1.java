package com.enviouse.futureshopsp.api.economy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Stable binary codec for the version one persisted account binding. */
public final class AccountBindingCodecV1 {
    public static final int CODEC_VERSION = 1;

    public static String encode(PersistedAccountBindingV1 binding) {
        if (binding == null) {
            throw new IllegalArgumentException("binding is required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(CODEC_VERSION);
            output.writeInt(binding.bindingSchema());
            writeString(output, binding.providerId());
            output.writeInt(binding.providerApiVersion());
            writeString(output, binding.adapterId());
            writeString(output, binding.adapterProtocol());
            writeString(output, binding.backendClassName());
            writeString(output, binding.backendSha256());
            output.writeBoolean(binding.backendSha512().isPresent());
            binding.backendSha512().ifPresent(value -> writeStringUnchecked(output, value));
            writeString(output, binding.durableBackendLineage());
            writeUuid(output, binding.accountUuid());
            writeString(output, binding.currencyId());
            output.writeInt(binding.currencyPrecision());
            writeString(output, binding.durableManagerIdentity());
            output.writeLong(binding.bindingGeneration());
            writeUuid(output, binding.rootRequestId().value());
            writeUuid(output, binding.legRequestId().value());
            writeString(output, binding.requestFingerprint());
            output.writeInt(binding.receiptProtocolVersion());
            output.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("account binding encoding failed", exception);
        }
    }

    public static PersistedAccountBindingV1 decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("encoded account binding is required");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int codecVersion = input.readInt();
            if (codecVersion != CODEC_VERSION) {
                throw new IllegalArgumentException("unsupported account binding codec");
            }
            int schema = input.readInt();
            String providerId = readString(input, 64);
            int providerApiVersion = input.readInt();
            String adapterId = readString(input, 128);
            String adapterProtocol = readString(input, 64);
            String backendClassName = readString(input, 256);
            String backendSha256 = readString(input, 128);
            Optional<String> backendSha512 = input.readBoolean()
                    ? Optional.of(readString(input, 128)) : Optional.empty();
            String durableBackendLineage = readString(input, 512);
            UUID accountUuid = readUuid(input);
            String currencyId = readString(input, 128);
            int currencyPrecision = input.readInt();
            String durableManagerIdentity = readString(input, 512);
            long bindingGeneration = input.readLong();
            RequestId rootRequestId = new RequestId(readUuid(input));
            RequestId legRequestId = new RequestId(readUuid(input));
            String requestFingerprint = readString(input, 128);
            int receiptProtocolVersion = input.readInt();
            if (input.available() != 0) {
                throw new IllegalArgumentException("account binding contains trailing data");
            }
            return new PersistedAccountBindingV1(schema, providerId, providerApiVersion, adapterId,
                    adapterProtocol, backendClassName, backendSha256, backendSha512,
                    durableBackendLineage, accountUuid, currencyId, currencyPrecision,
                    durableManagerIdentity, bindingGeneration, rootRequestId, legRequestId,
                    requestFingerprint, receiptProtocolVersion);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (EOFException | IllegalStateException exception) {
            throw new IllegalArgumentException("truncated account binding", exception);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("invalid account binding", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8192) {
            throw new IllegalArgumentException("account binding field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeStringUnchecked(DataOutputStream output, String value) {
        try {
            writeString(output, value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readString(DataInputStream input, int maxChars) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 8192) {
            throw new IllegalArgumentException("invalid account binding field length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("account binding field is truncated");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.length() > maxChars || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("account binding field is outside bounds");
        }
        return value;
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
