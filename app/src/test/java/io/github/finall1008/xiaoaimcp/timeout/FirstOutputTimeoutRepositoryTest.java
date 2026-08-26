package io.github.finall1008.xiaoaimcp.timeout;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class FirstOutputTimeoutRepositoryTest {
    @Test
    public void missingPreferencesFollowHost() {
        FirstOutputTimeoutConfig config = new FirstOutputTimeoutRepository(
                fakePreferences(new HashMap<>())
        ).load();

        assertEquals(FirstOutputTimeoutMode.HOST_DEFAULT, config.mode());
        assertEquals(FirstOutputTimeoutConfig.DEFAULT_CUSTOM_SECONDS, config.customSeconds());
    }

    @Test
    public void savesAndLoadsAllConfigurationFields() {
        Map<String, Object> values = new HashMap<>();
        FirstOutputTimeoutRepository repository = new FirstOutputTimeoutRepository(
                fakePreferences(values)
        );
        FirstOutputTimeoutConfig expected = new FirstOutputTimeoutConfig(
                FirstOutputTimeoutMode.CUSTOM,
                345L
        );

        repository.save(expected);

        assertEquals(expected, repository.load());
        assertEquals(
                FirstOutputTimeoutMode.CUSTOM.preferenceValue(),
                values.get(BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_MODE)
        );
        assertEquals(345L, values.get(BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_SECONDS));
    }

    @Test
    public void malformedPreferencesFailClosedToHostDefault() {
        Map<String, Object> values = new HashMap<>();
        values.put(BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_MODE, "unknown");
        values.put(BridgeContract.PREF_FIRST_OUTPUT_TIMEOUT_SECONDS, -1L);

        FirstOutputTimeoutConfig config = new FirstOutputTimeoutRepository(
                fakePreferences(values)
        ).load();

        assertEquals(FirstOutputTimeoutConfig.hostDefault(), config);
    }

    private static SharedPreferences fakePreferences(Map<String, Object> values) {
        Object[] editorHolder = new Object[1];
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                FirstOutputTimeoutRepositoryTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "putString", "putLong" -> {
                        values.put((String) args[0], args[1]);
                        yield proxy;
                    }
                    case "apply" -> null;
                    case "commit" -> true;
                    default -> proxy;
                }
        );
        editorHolder[0] = editor;
        return (SharedPreferences) Proxy.newProxyInstance(
                FirstOutputTimeoutRepositoryTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        Object value = values.get(args[0]);
                        yield value == null ? args[1] : (String) value;
                    }
                    case "getLong" -> {
                        Object value = values.get(args[0]);
                        yield value == null ? args[1] : (Long) value;
                    }
                    case "edit" -> editorHolder[0];
                    case "contains" -> values.containsKey(args[0]);
                    case "getAll" -> Map.copyOf(values);
                    default -> null;
                }
        );
    }
}
