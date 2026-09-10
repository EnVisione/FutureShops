package com.enviouse.futureshopsp.server.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugDiagnosticsTest {
    @AfterEach
    void reset() {
        DebugDiagnostics.reset();
    }

    @Test
    void startsOffAndUsesTheBoundedModuleAllowlist() {
        assertEquals("debug=off", DebugDiagnostics.statusLine());
        assertFalse(DebugDiagnostics.enabled(DebugModule.PROVIDER));
        assertEquals(DebugModule.PIXELMON, DebugModule.parse(" PIXELMON ").orElseThrow());
        assertEquals(DebugModule.DANCONOMY, DebugModule.parse(" DANCONOMY ").orElseThrow());
        assertTrue(DebugModule.parse("all").isPresent());
        assertTrue(DebugModule.parse("not-a-module").isEmpty());
    }

    @Test
    void enablingCreatesAnEphemeralSessionAndResetDisablesIt() {
        DebugDiagnostics.DebugToggleResult enabled = DebugDiagnostics.enable(DebugModule.RECEIPT);
        assertTrue(enabled.changed());
        assertTrue(DebugDiagnostics.enabled(DebugModule.RECEIPT));
        assertFalse(DebugDiagnostics.enabled(DebugModule.PROVIDER));
        assertTrue(DebugDiagnostics.statusLine().contains("module=receipt"));

        DebugDiagnostics.reset();
        assertEquals("debug=off", DebugDiagnostics.statusLine());
        assertFalse(DebugDiagnostics.enabled(DebugModule.RECEIPT));
    }

    @Test
    void commandAndMixinResourcesDeclareTheCanonicalProcedure() throws Exception {
        Path root = projectRoot();
        String command = Files.readString(root.resolve("src/main/java/com/enviouse/futureshopsp/command/DebugCommand.java"));
        String mixin = Files.readString(root.resolve("src/main/resources/futureshops-pixelmon.mixins.json"));
        String danconomyMixin = Files.readString(root.resolve("src/main/resources/futureshops-danconomy.mixins.json"));
        String mixinSource = Files.readString(root.resolve(
                "src/main/java/com/enviouse/futureshopsp/mixin/PixelmonPlayerPartyStorageMixin.java"));
        assertTrue(command.contains("literal(\"futureshops\")"));
        assertTrue(command.contains("literal(\"debug\")"));
        assertTrue(command.contains("literal(\"on\")"));
        assertTrue(command.contains("literal(\"off\")"));
        assertTrue(command.contains("literal(\"status\")"));
        assertTrue(mixin.contains("PixelmonPlayerPartyStorageMixin"));
        assertTrue(mixin.contains("\"required\": false"));
        assertTrue(mixinSource.contains("Tag rawReceipts = tag.get(FUTURESHOPS_RECEIPTS)"));
        assertTrue(mixinSource.contains("Tag rawEntries = root.get(FUTURESHOPS_RECEIPT_ENTRIES)"));
        assertTrue(mixinSource.contains("if (!(rawEntry instanceof CompoundTag entry))"));
        assertTrue(mixinSource.contains("futureshopsUnknownReceiptRecords.add(rawEntry.copy())"));
        assertTrue(danconomyMixin.contains("DanConomyLedgerDataMixin"));
        assertTrue(danconomyMixin.contains("\"required\": false"));
        String diagnostics = Files.readString(root.resolve(
                "src/main/java/com/enviouse/futureshopsp/server/debug/DebugDiagnostics.java"));
        assertTrue(diagnostics.contains("LoggerFactory.getLogger(CATEGORY)"));
        assertTrue(diagnostics.contains("request_id="));
        assertTrue(diagnostics.contains("SCHEMA_VERSION = 2"));
        assertTrue(diagnostics.contains("provider_declared_capabilities="));
        assertTrue(diagnostics.contains("account_observed_capabilities="));
        assertTrue(diagnostics.contains("next_action="));
        assertTrue(diagnostics.contains("FutureShops-Source-Commit"));
        assertTrue(diagnostics.contains("discoverArtifactHash"));
        assertTrue(diagnostics.contains("lifecycleState"));
    }

    private static Path projectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root is unavailable");
    }
}
