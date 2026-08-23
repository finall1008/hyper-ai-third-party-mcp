package io.github.finall1008.xiaoaimcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TargetVersionPolicyTest {
    @Test
    public void acceptsVersionEightAndNewer() {
        assertTrue(TargetVersionPolicy.isSupported("8"));
        assertTrue(TargetVersionPolicy.isSupported("8.0.30.4121"));
        assertTrue(TargetVersionPolicy.isSupported("  V8.2  "));
        assertTrue(TargetVersionPolicy.isSupported("9.x"));
        assertEquals(8, TargetVersionPolicy.parseMajor("v8.0").orElseThrow());
    }

    @Test
    public void rejectsOlderMissingAndMalformedVersions() {
        assertFalse(TargetVersionPolicy.isSupported("7.9.99"));
        assertFalse(TargetVersionPolicy.isSupported(null));
        assertFalse(TargetVersionPolicy.isSupported(""));
        assertFalse(TargetVersionPolicy.isSupported("beta8"));
        assertFalse(TargetVersionPolicy.isSupported("8beta"));
        assertFalse(TargetVersionPolicy.isSupported(".8"));
    }
}
