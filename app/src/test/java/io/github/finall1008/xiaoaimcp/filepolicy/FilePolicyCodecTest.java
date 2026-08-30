package io.github.finall1008.xiaoaimcp.filepolicy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class FilePolicyCodecTest {
    @Test
    public void roundTripsRulesAndCapabilities() {
        FilePolicyConfig input = new FilePolicyConfig(true, List.of(
                new FileAccessRule("/sdcard/Download/", true, true,
                        false, true, false,
                        MutationConfirmationPolicy.BACKGROUND_AUTOMATIC)
        ));

        FilePolicyConfig decoded = FilePolicyCodec.parse(FilePolicyCodec.encode(input));

        assertTrue(decoded.enabled());
        assertEquals(1, decoded.rules().size());
        FileAccessRule rule = decoded.rules().get(0);
        assertEquals("/sdcard/Download", rule.path());
        assertTrue(rule.allowMutation());
        assertTrue(rule.allowLockscreenRead());
        assertFalse(rule.allowLockscreenMutation());
        assertTrue(rule.allowBackgroundMutation());
        assertFalse(rule.allowRecursiveDelete());
        assertEquals(MutationConfirmationPolicy.BACKGROUND_AUTOMATIC,
                rule.confirmationPolicy());
    }

    @Test
    public void rejectsUnsupportedAndTraversalPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> FilePolicyCodec.normalizeConfiguredPath("/data/local/tmp"));
        assertThrows(IllegalArgumentException.class,
                () -> FilePolicyCodec.normalizeConfiguredPath("/sdcard/Download/../DCIM"));
        assertThrows(IllegalArgumentException.class,
                () -> FilePolicyCodec.normalizeConfiguredPath("/sdcard\\Download"));
    }

    @Test
    public void rejectsDuplicateNormalizedPaths() {
        String json = "{\"version\":1,\"enabled\":true,\"rules\":["
                + "{\"path\":\"/sdcard/Download\"},"
                + "{\"path\":\"/sdcard/Download/\"}]}";

        assertThrows(IllegalArgumentException.class, () -> FilePolicyCodec.parse(json));
    }

    @Test
    public void canonicalizesSdcardAliasesAndRejectsAliasDuplicates() {
        assertEquals("/sdcard/Download", FilePolicyCodec.normalizeConfiguredPath(
                "/storage/emulated/0/Download"));
        String json = "{\"version\":2,\"enabled\":true,\"rules\":["
                + "{\"path\":\"/sdcard/Download\"},"
                + "{\"path\":\"/storage/emulated/0/Download\"}]}";

        assertThrows(IllegalArgumentException.class, () -> FilePolicyCodec.parse(json));
        assertThrows(IllegalArgumentException.class, () -> FilePolicyCodec.encode(
                new FilePolicyConfig(true, List.of(
                        new FileAccessRule("/sdcard/Download", false,
                                false, false, false, false),
                        new FileAccessRule("/storage/emulated/0/Download", true,
                                true, true, true, true)
                ))));
    }

    @Test
    public void migratesVersionOneRulesToAskEveryTime() throws Exception {
        String json = "{\"version\":1,\"enabled\":true,\"rules\":["
                + "{\"path\":\"/sdcard/Download\","
                + "\"allow_mutation\":true,\"allow_background_mutation\":true}]}";

        FileAccessRule rule = FilePolicyCodec.parse(json).rules().get(0);

        assertEquals(MutationConfirmationPolicy.ASK_EVERY_TIME,
                rule.confirmationPolicy());
        assertEquals(2, new org.json.JSONObject(FilePolicyCodec.encode(
                new FilePolicyConfig(true, List.of(rule)))).getInt("version"));
    }
}
