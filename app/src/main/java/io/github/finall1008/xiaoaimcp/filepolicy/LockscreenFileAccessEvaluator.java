package io.github.finall1008.xiaoaimcp.filepolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Evaluates only file operations whose command grammar can be identified unambiguously. */
public final class LockscreenFileAccessEvaluator {
    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "list_files", "search_files", "file_info", "file_grep"
    );
    private static final Set<String> MUTATION_TOOLS = Set.of(
            "write_file", "append_file", "edit_file", "copy_file", "move_file"
    );
    private static final Set<String> FIND_EXEC_ACTIONS = Set.of(
            "-exec", "-execdir", "-ok", "-okdir"
    );
    private static final Set<String> FIND_OUTPUT_ACTIONS = Set.of(
            "-fprint", "-fprint0", "-fprintf", "-fls"
    );

    private LockscreenFileAccessEvaluator() {
    }

    public static boolean isDirectToolAllowed(
            FilePolicyConfig config,
            String toolName,
            Map<String, String> arguments
    ) {
        if (toolName == null || arguments == null) {
            return false;
        }
        if (READ_TOOLS.contains(toolName)) {
            return allPathsAllowed(config, pathsFor(toolName, arguments), false, true);
        }
        if (toolName.equals("copy_file")) {
            String source = arguments.get("source");
            String destination = arguments.get("destination");
            return source != null
                    && destination != null
                    && PathPolicyEvaluator.canLockscreenRead(config, source)
                    && PathPolicyEvaluator.canLockscreenMutate(config, destination);
        }
        if (MUTATION_TOOLS.contains(toolName)) {
            return allPathsAllowed(config, pathsFor(toolName, arguments), true, true);
        }
        if (toolName.equals("delete_file")) {
            List<String> paths = pathsFor(toolName, arguments);
            if (!allPathsAllowed(config, paths, true, true)) {
                return false;
            }
            for (String path : paths) {
                if (new File(stripQuery(path)).isDirectory()
                        && !PathPolicyEvaluator.canRecursiveDelete(config, path)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public static boolean isCliCommandAllowed(
            FilePolicyConfig config,
            String commandName,
            List<String> arguments
    ) {
        if (config == null || !config.enabled() || commandName == null || arguments == null) {
            return false;
        }
        for (String argument : arguments) {
            if (argument == null || containsUnsafeShellSyntax(argument)) {
                return false;
            }
        }
        CommandPaths paths = parseCommand(commandName.toLowerCase(Locale.ROOT), arguments);
        if (paths == null || (paths.reads().isEmpty() && paths.mutations().isEmpty())) {
            return false;
        }
        if (!allPathsAllowed(config, paths.reads(), false, false)
                || !allPathsAllowed(config, paths.mutations(), true, false)) {
            return false;
        }
        if (paths.recursiveDelete()) {
            for (String path : paths.mutations()) {
                if (!PathPolicyEvaluator.canRecursiveDelete(config, path)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static CommandPaths parseCommand(String command, List<String> arguments) {
        return switch (command) {
            case "ls" -> readOnly(simpleOperands(arguments, "aAbBCdFghHiklLmnopqQrRsStuUvwxXZ1",
                    Set.of("--all", "--almost-all", "--directory", "--classify",
                            "--human-readable", "--inode", "--numeric-uid-gid", "--reverse")));
            case "cat" -> readOnly(simpleOperands(arguments, "AbEnsTuv",
                    Set.of("--show-all", "--number-nonblank", "--show-ends", "--number",
                            "--squeeze-blank", "--show-tabs", "--show-nonprinting")));
            case "head", "tail" -> readOnly(headTailOperands(arguments));
            case "wc" -> readOnly(simpleOperands(arguments, "clmwL",
                    Set.of("--bytes", "--chars", "--lines", "--max-line-length", "--words")));
            case "stat" -> readOnly(statOperands(arguments));
            case "grep" -> readOnly(grepOperands(arguments));
            case "find" -> findPaths(arguments);
            case "sed" -> sedPaths(arguments);
            case "cp" -> copyMovePaths(arguments, false);
            case "mv" -> copyMovePaths(arguments, true);
            case "mkdir" -> mutateOnly(simpleOperands(arguments, "pv",
                    Set.of("--parents", "--verbose")));
            case "touch" -> mutateOnly(simpleOperands(arguments, "acm",
                    Set.of("--no-create")));
            case "rm" -> rmPaths(arguments);
            default -> null;
        };
    }

    private static List<String> simpleOperands(
            List<String> arguments,
            String allowedShort,
            Set<String> allowedLong
    ) {
        List<String> operands = new ArrayList<>();
        boolean options = true;
        for (String argument : arguments) {
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && argument.startsWith("--")) {
                if (!allowedLong.contains(argument)) {
                    return null;
                }
                continue;
            }
            if (options && isShortOption(argument)) {
                if (!shortClusterAllowed(argument, allowedShort)) {
                    return null;
                }
                continue;
            }
            if (!isAbsolutePath(argument)) {
                return null;
            }
            operands.add(argument);
        }
        return operands.isEmpty() ? null : operands;
    }

    private static List<String> headTailOperands(List<String> arguments) {
        List<String> operands = new ArrayList<>();
        boolean options = true;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && (argument.equals("-q") || argument.equals("-v")
                    || argument.equals("-z") || argument.equals("--quiet")
                    || argument.equals("--silent") || argument.equals("--verbose")
                    || argument.equals("--zero-terminated"))) {
                continue;
            }
            if (options && (argument.equals("-n") || argument.equals("-c"))) {
                if (++index >= arguments.size() || !isCount(arguments.get(index))) {
                    return null;
                }
                continue;
            }
            if (options && (argument.matches("-[nc][+-]?\\d+")
                    || argument.matches("-\\d+")
                    || argument.matches("--(?:lines|bytes)=[+-]?\\d+"))) {
                continue;
            }
            if (!isAbsolutePath(argument)) {
                return null;
            }
            operands.add(argument);
        }
        return operands.isEmpty() ? null : operands;
    }

    private static List<String> statOperands(List<String> arguments) {
        List<String> operands = new ArrayList<>();
        boolean options = true;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && (argument.equals("-L") || argument.equals("-f")
                    || argument.equals("-t") || argument.equals("--dereference")
                    || argument.equals("--file-system") || argument.equals("--terse"))) {
                continue;
            }
            if (options && (argument.equals("-c") || argument.equals("--format")
                    || argument.equals("--printf"))) {
                if (++index >= arguments.size()) {
                    return null;
                }
                continue;
            }
            if (options && (argument.startsWith("--format=")
                    || argument.startsWith("--printf="))) {
                continue;
            }
            if (!isAbsolutePath(argument)) {
                return null;
            }
            operands.add(argument);
        }
        return operands.isEmpty() ? null : operands;
    }

    private static List<String> grepOperands(List<String> arguments) {
        List<String> files = new ArrayList<>();
        boolean options = true;
        boolean hasExplicitPattern = false;
        boolean consumedPattern = false;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && (argument.equals("-e") || argument.equals("--regexp"))) {
                if (++index >= arguments.size()) {
                    return null;
                }
                hasExplicitPattern = true;
                continue;
            }
            if (options && ((argument.startsWith("-e") && argument.length() > 2)
                    || argument.startsWith("--regexp="))) {
                hasExplicitPattern = true;
                continue;
            }
            if (options && (argument.matches("-[EFGHinovwxy]+")
                    || Set.of("--extended-regexp", "--fixed-strings", "--basic-regexp",
                            "--with-filename", "--no-filename", "--ignore-case",
                            "--line-number", "--only-matching", "--invert-match",
                            "--word-regexp", "--line-regexp").contains(argument))) {
                continue;
            }
            if (!hasExplicitPattern && !consumedPattern) {
                consumedPattern = true;
                options = false;
                continue;
            }
            if (!isAbsolutePath(argument)) {
                return null;
            }
            files.add(argument);
        }
        return files.isEmpty() ? null : files;
    }

    private static CommandPaths findPaths(List<String> arguments) {
        List<String> roots = new ArrayList<>();
        List<String> extraReads = new ArrayList<>();
        boolean expression = false;
        boolean delete = false;
        for (String argument : arguments) {
            if (!expression && !argument.startsWith("-")) {
                if (!isAbsolutePath(argument)) {
                    return null;
                }
                roots.add(argument);
                continue;
            }
            expression = true;
            if (FIND_EXEC_ACTIONS.contains(argument) || FIND_OUTPUT_ACTIONS.contains(argument)) {
                return null;
            }
            if (argument.equals("-delete")) {
                delete = true;
            } else if (isAbsolutePath(argument)) {
                extraReads.add(argument);
            }
        }
        if (roots.isEmpty()) {
            return null;
        }
        if (delete) {
            return new CommandPaths(extraReads, roots, true);
        }
        roots.addAll(extraReads);
        return new CommandPaths(roots, List.of(), false);
    }

    private static CommandPaths sedPaths(List<String> arguments) {
        List<String> scripts = new ArrayList<>();
        List<String> files = new ArrayList<>();
        boolean inPlace = false;
        boolean options = true;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && (argument.equals("-n") || argument.equals("-E")
                    || argument.equals("-r") || argument.equals("--quiet")
                    || argument.equals("--silent") || argument.equals("--regexp-extended"))) {
                continue;
            }
            if (options && (argument.equals("-i") || argument.equals("--in-place")
                    || (argument.startsWith("-i") && argument.length() > 2)
                    || argument.startsWith("--in-place="))) {
                inPlace = true;
                continue;
            }
            if (options && argument.equals("-e")) {
                if (++index >= arguments.size()) {
                    return null;
                }
                scripts.add(arguments.get(index));
                continue;
            }
            if (options && argument.startsWith("-e") && argument.length() > 2) {
                scripts.add(argument.substring(2));
                continue;
            }
            if (scripts.isEmpty()) {
                scripts.add(argument);
                options = false;
            } else {
                if (!isAbsolutePath(argument)) {
                    return null;
                }
                files.add(argument);
            }
        }
        if (!inPlace || files.isEmpty() || scripts.isEmpty()) {
            return null;
        }
        for (String script : scripts) {
            if (!isSafeSedSubstitution(script)) {
                return null;
            }
        }
        return new CommandPaths(List.of(), files, false);
    }

    private static boolean isSafeSedSubstitution(String script) {
        if (script == null || script.length() < 4 || script.charAt(0) != 's') {
            return false;
        }
        char delimiter = script.charAt(1);
        if (Character.isLetterOrDigit(delimiter) || delimiter == '\\') {
            return false;
        }
        int separators = 0;
        boolean escaped = false;
        int finalSeparator = -1;
        for (int index = 2; index < script.length(); index++) {
            char value = script.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == delimiter) {
                separators++;
                finalSeparator = index;
                if (separators == 2) {
                    break;
                }
            }
        }
        if (separators != 2) {
            return false;
        }
        String flags = script.substring(finalSeparator + 1);
        return flags.matches("[0-9gIpM]*");
    }

    private static CommandPaths copyMovePaths(List<String> arguments, boolean move) {
        List<String> operands = new ArrayList<>();
        String targetDirectory = null;
        boolean options = true;
        String allowedShort = move ? "bfintTuv" : "afHilnprRstTuvx";
        Set<String> allowedLong = move
                ? Set.of("--force", "--interactive", "--no-clobber", "--no-target-directory",
                        "--update", "--verbose")
                : Set.of("--archive", "--force", "--interactive", "--link", "--no-clobber",
                        "--no-dereference", "--no-target-directory", "--preserve",
                        "--recursive", "--symbolic-link", "--update", "--verbose");
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && (argument.equals("-t") || argument.equals("--target-directory"))) {
                if (++index >= arguments.size() || targetDirectory != null
                        || !isAbsolutePath(arguments.get(index))) {
                    return null;
                }
                targetDirectory = arguments.get(index);
                continue;
            }
            if (options && argument.startsWith("--target-directory=")) {
                String value = argument.substring("--target-directory=".length());
                if (targetDirectory != null || !isAbsolutePath(value)) {
                    return null;
                }
                targetDirectory = value;
                continue;
            }
            if (options && argument.startsWith("--")) {
                if (!allowedLong.contains(argument) && !argument.startsWith("--backup=")) {
                    return null;
                }
                continue;
            }
            if (options && isShortOption(argument)) {
                if (argument.indexOf('t', 1) >= 0 || !shortClusterAllowed(argument, allowedShort)) {
                    return null;
                }
                continue;
            }
            if (!isAbsolutePath(argument)) {
                return null;
            }
            operands.add(argument);
        }
        int minimum = targetDirectory == null ? 2 : 1;
        if (operands.size() < minimum) {
            return null;
        }
        if (move) {
            List<String> mutations = new ArrayList<>(operands);
            if (targetDirectory != null) {
                mutations.add(targetDirectory);
            }
            return new CommandPaths(List.of(), mutations, false);
        }
        List<String> reads = new ArrayList<>(operands);
        String destination = targetDirectory != null
                ? targetDirectory : reads.remove(reads.size() - 1);
        return new CommandPaths(reads, List.of(destination), false);
    }

    private static CommandPaths rmPaths(List<String> arguments) {
        List<String> paths = new ArrayList<>();
        boolean recursive = false;
        boolean options = true;
        for (String argument : arguments) {
            if (options && argument.equals("--")) {
                options = false;
                continue;
            }
            if (options && argument.startsWith("--")) {
                if (argument.equals("--recursive")) {
                    recursive = true;
                } else if (!Set.of("--force", "--interactive", "--one-file-system",
                        "--preserve-root", "--no-preserve-root", "--verbose", "--dir")
                        .contains(argument) && !argument.startsWith("--interactive=")) {
                    return null;
                }
                continue;
            }
            if (options && isShortOption(argument)) {
                if (!shortClusterAllowed(argument, "firdvIR")) {
                    return null;
                }
                recursive |= argument.indexOf('r', 1) >= 0 || argument.indexOf('R', 1) >= 0;
                continue;
            }
            if (!isAbsolutePath(argument)) {
                return null;
            }
            paths.add(argument);
        }
        return paths.isEmpty() ? null : new CommandPaths(List.of(), paths, recursive);
    }

    private static CommandPaths readOnly(List<String> paths) {
        return paths == null ? null : new CommandPaths(paths, List.of(), false);
    }

    private static CommandPaths mutateOnly(List<String> paths) {
        return paths == null ? null : new CommandPaths(List.of(), paths, false);
    }

    private static boolean allPathsAllowed(
            FilePolicyConfig config,
            List<String> paths,
            boolean mutate,
            boolean requirePath
    ) {
        if (requirePath && paths.isEmpty()) {
            return false;
        }
        for (String path : paths) {
            boolean allowed = mutate
                    ? PathPolicyEvaluator.canLockscreenMutate(config, path)
                    : PathPolicyEvaluator.canLockscreenRead(config, path);
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static List<String> pathsFor(String toolName, Map<String, String> arguments) {
        List<String> paths = new ArrayList<>();
        if (toolName.equals("copy_file") || toolName.equals("move_file")) {
            addIfPresent(paths, arguments.get("source"));
            addIfPresent(paths, arguments.get("destination"));
            return paths;
        }
        if (toolName.equals("search_files")) {
            addIfPresent(paths, arguments.get("folderPath"));
            addIfPresent(paths, arguments.get("folder_path"));
            addIfPresent(paths, arguments.get("path"));
            return paths;
        }
        addIfPresent(paths, arguments.get("path"));
        return paths;
    }

    private static boolean containsUnsafeShellSyntax(String value) {
        return value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf(';') >= 0
                || value.indexOf('&') >= 0
                || value.indexOf('|') >= 0
                || value.indexOf('>') >= 0
                || value.indexOf('<') >= 0
                || value.contains("$(")
                || value.contains("${")
                || value.contains("`");
    }

    private static boolean isShortOption(String value) {
        return value.length() > 1 && value.charAt(0) == '-' && value.charAt(1) != '-';
    }

    private static boolean shortClusterAllowed(String value, String allowed) {
        for (int index = 1; index < value.length(); index++) {
            if (allowed.indexOf(value.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCount(String value) {
        return value != null && value.matches("[+-]?\\d+");
    }

    private static boolean isAbsolutePath(String value) {
        return value != null && value.startsWith("/") && !value.startsWith("//");
    }

    private static void addIfPresent(List<String> paths, String path) {
        if (path != null && !path.isBlank() && !paths.contains(path)) {
            paths.add(path);
        }
    }

    private static String stripQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private record CommandPaths(
            List<String> reads,
            List<String> mutations,
            boolean recursiveDelete
    ) {
    }
}
