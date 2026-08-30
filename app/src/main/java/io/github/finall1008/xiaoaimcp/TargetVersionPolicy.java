package io.github.finall1008.xiaoaimcp;

import java.util.OptionalInt;

public final class TargetVersionPolicy {
    private TargetVersionPolicy() {
    }

    public static boolean isSupported(String versionName) {
        OptionalInt major = parseMajor(versionName);
        return major.isPresent() && major.getAsInt() >= BridgeContract.MIN_TARGET_MAJOR_VERSION;
    }

    public static boolean hasNativeMcp(String versionName) {
        int[] version = parseNumericVersion(versionName);
        int[] minimum = parseNumericVersion(BridgeContract.NATIVE_MCP_VERSION_NAME);
        if (version == null || minimum == null) {
            return false;
        }
        int length = Math.max(version.length, minimum.length);
        for (int index = 0; index < length; index++) {
            int current = index < version.length ? version[index] : 0;
            int required = index < minimum.length ? minimum[index] : 0;
            if (current != required) {
                return current > required;
            }
        }
        return true;
    }

    public static OptionalInt parseMajor(String versionName) {
        if (versionName == null) {
            return OptionalInt.empty();
        }
        String normalized = versionName.trim();
        if (normalized.isEmpty()) {
            return OptionalInt.empty();
        }
        if (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V') {
            normalized = normalized.substring(1);
        }
        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        if (end == 0 || (end < normalized.length() && normalized.charAt(end) != '.')) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(normalized.substring(0, end)));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private static int[] parseNumericVersion(String versionName) {
        if (versionName == null) {
            return null;
        }
        String normalized = versionName.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            return null;
        }
        String[] components = normalized.split("\\.", -1);
        int[] parsed = new int[components.length];
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            if (component.isEmpty()) {
                return null;
            }
            for (int character = 0; character < component.length(); character++) {
                if (!Character.isDigit(component.charAt(character))) {
                    return null;
                }
            }
            try {
                parsed[index] = Integer.parseInt(component);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return parsed;
    }
}
