package io.github.finall1008.xiaoaimcp.prompt;

import java.util.ArrayList;
import java.util.List;

public final class PromptLineDiff {
    private static final long MAX_LCS_CELLS = 4_000_000L;

    private PromptLineDiff() {
    }

    public static List<Line> calculate(String original, String patched) {
        String[] before = original.split("\\R", -1);
        String[] after = patched.split("\\R", -1);
        if ((long) before.length * after.length > MAX_LCS_CELLS) {
            return coarseDiff(before, after);
        }
        int[][] lcs = new int[before.length + 1][after.length + 1];
        for (int left = before.length - 1; left >= 0; left--) {
            for (int right = after.length - 1; right >= 0; right--) {
                lcs[left][right] = before[left].equals(after[right])
                        ? lcs[left + 1][right + 1] + 1
                        : Math.max(lcs[left + 1][right], lcs[left][right + 1]);
            }
        }
        List<Line> result = new ArrayList<>();
        int left = 0;
        int right = 0;
        int oldLine = 1;
        int newLine = 1;
        while (left < before.length || right < after.length) {
            if (left < before.length && right < after.length
                    && before[left].equals(after[right])) {
                result.add(new Line(Type.UNCHANGED, before[left], oldLine++, newLine++));
                left++;
                right++;
            } else if (right >= after.length || left < before.length
                    && lcs[left + 1][right] >= lcs[left][right + 1]) {
                result.add(new Line(Type.REMOVED, before[left++], oldLine++, null));
            } else {
                result.add(new Line(Type.ADDED, after[right++], null, newLine++));
            }
        }
        return List.copyOf(result);
    }

    private static List<Line> coarseDiff(String[] before, String[] after) {
        int prefix = 0;
        while (prefix < before.length && prefix < after.length
                && before[prefix].equals(after[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < before.length - prefix && suffix < after.length - prefix
                && before[before.length - 1 - suffix]
                .equals(after[after.length - 1 - suffix])) {
            suffix++;
        }
        List<Line> result = new ArrayList<>();
        int oldLine = 1;
        int newLine = 1;
        for (int index = 0; index < prefix; index++) {
            result.add(new Line(Type.UNCHANGED, before[index], oldLine++, newLine++));
        }
        for (int index = prefix; index < before.length - suffix; index++) {
            result.add(new Line(Type.REMOVED, before[index], oldLine++, null));
        }
        for (int index = prefix; index < after.length - suffix; index++) {
            result.add(new Line(Type.ADDED, after[index], null, newLine++));
        }
        for (int index = before.length - suffix; index < before.length; index++) {
            result.add(new Line(Type.UNCHANGED, before[index], oldLine++, newLine++));
        }
        return List.copyOf(result);
    }

    public enum Type { UNCHANGED, REMOVED, ADDED }

    public record Line(Type type, String text, Integer oldLine, Integer newLine) {
    }
}
