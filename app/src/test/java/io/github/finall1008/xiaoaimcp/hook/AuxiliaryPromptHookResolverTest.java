package io.github.finall1008.xiaoaimcp.hook;

import android.content.Context;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AuxiliaryPromptHookResolverTest {
    @Test
    public void structurallyResolvesIndependentToolAndMemoryLoaders() throws Exception {
        AuxiliaryPromptHookTargets targets = AuxiliaryPromptHookResolver.resolve(
                getClass().getClassLoader(),
                () -> names(RelocatedToolLoader.class, RelocatedMemoryLoader.class),
                DexDiscoveryHints.empty());

        assertEquals("verified-profile+structural-discovery", targets.mode());
        assertEquals("loadDescriptions", targets.toolPromptLoader().getName());
        assertEquals("loadTemplate", targets.memoryPromptLoader().getName());
        assertTrue(targets.hasAllCapabilities());
    }

    private static List<String> names(Class<?>... classes) {
        return Arrays.stream(classes).map(Class::getName).toList();
    }

    public static final class FakeFs {
    }

    public static final class RelocatedToolLoader {
        public static final RelocatedToolLoader INSTANCE = new RelocatedToolLoader();

        public Map<String, String> loadDescriptions(
                FakeFs fs,
                String toolName,
                Map<String, Boolean> variables
        ) {
            return Map.of("description", toolName);
        }

        public Map<String, String> parse(String content) {
            return Map.of("description", content);
        }
    }

    public static final class RelocatedMemoryLoader {
        public static final RelocatedMemoryLoader INSTANCE = new RelocatedMemoryLoader();

        public Map<String, String> loadTemplate(
                String promptKey,
                Map<String, String> variables
        ) {
            return Map.of("systemPrompt", promptKey);
        }

        public void initialize(Context context) {
        }
    }
}
