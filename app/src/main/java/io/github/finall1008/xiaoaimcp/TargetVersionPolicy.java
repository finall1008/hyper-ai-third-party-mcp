package io.github.finall1008.xiaoaimcp;

import java.util.OptionalInt;

public final class TargetVersionPolicy {
    private TargetVersionPolicy() {
    }

    public static boolean isSupported(String versionName) {
        OptionalInt major = parseMajor(versionName);
        return major.isPresent() && major.getAsInt() >= BridgeContract.MIN_TARGET_MAJOR_VERSION;
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
}
