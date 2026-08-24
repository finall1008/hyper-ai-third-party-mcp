package io.github.finall1008.xiaoaimcp.filepolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LockscreenFileAccessEvaluator {
    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "list_files", "search_files", "file_info", "file_grep"
    );
    private static final Set<String> MUTATION_TOOLS = Set.of(
            "write_file", "append_file", "edit_file", "copy_file", "move_file"
    );
    private static final Set<String> READ_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "wc", "stat", "test", "grep", "find"
    );
    private static final Set<String> MUTATION_COMMANDS = Set.of(
            "sed", "cp", "mv", "mkdir", "touch"
    );
    private static final Set<String> DELETE_COMMANDS = Set.of("rm");

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
            return allPathsAllowed(config, pathsFor(toolName, arguments), false);
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
            return allPathsAllowed(config, pathsFor(toolName, arguments), true);
        }
        if (toolName.equals("delete_file")) {
            List<String> paths = pathsFor(toolName, arguments);
            if (!allPathsAllowed(config, paths, true)) {
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
        String command = commandName.toLowerCase(Locale.ROOT);
        if (!READ_COMMANDS.contains(command)
                && !MUTATION_COMMANDS.contains(command)
                && !DELETE_COMMANDS.contains(command)) {
            return false;
        }
        for (String argument : arguments) {
            if (containsUnsafeShellSyntax(argument)) {
                return false;
            }
        }
        if (command.equals("find") && containsAny(arguments,
                "-exec", "-execdir", "-ok", "-okdir")) {
            return false;
        }

        List<String> paths = absolutePaths(arguments);
        int minimumPaths = command.equals("cp") || command.equals("mv") ? 2 : 1;
        if (paths.size() < minimumPaths) {
            return false;
        }

        boolean findDelete = command.equals("find") && arguments.contains("-delete");
        boolean delete = DELETE_COMMANDS.contains(command) || findDelete;
        boolean mutate = delete || MUTATION_COMMANDS.contains(command);
        if (command.equals("cp")) {
            if (!copyPathsAllowed(config, paths)) {
                return false;
            }
        } else if (!allPathsAllowed(config, paths, mutate)) {
            return false;
        }

        boolean recursive = findDelete || delete && containsAny(arguments,
                "-r", "-R", "--recursive", "-rf", "-fr", "-rF", "-Rf");
        if (recursive) {
            for (String path : paths) {
                if (!PathPolicyEvaluator.canRecursiveDelete(config, path)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean copyPathsAllowed(FilePolicyConfig config, List<String> paths) {
        int destinationIndex = paths.size() - 1;
        for (int index = 0; index < paths.size(); index++) {
            boolean allowed = index == destinationIndex
                    ? PathPolicyEvaluator.canLockscreenMutate(config, paths.get(index))
                    : PathPolicyEvaluator.canLockscreenRead(config, paths.get(index));
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

    private static boolean allPathsAllowed(
            FilePolicyConfig config,
            List<String> paths,
            boolean mutate
    ) {
        if (paths.isEmpty()) {
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

    private static List<String> absolutePaths(List<String> arguments) {
        List<String> paths = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.startsWith("/")) {
                paths.add(argument);
            }
        }
        return paths;
    }

    private static boolean containsUnsafeShellSyntax(String value) {
        return value.contains("$(")
                || value.contains("${")
                || value.contains("`")
                || value.equals(">")
                || value.equals(">>")
                || value.equals("<")
                || value.startsWith("1>")
                || value.startsWith("2>");
    }

    private static boolean containsAny(List<String> values, String... candidates) {
        for (String value : values) {
            for (String candidate : candidates) {
                if (value.equals(candidate)) {
                    return true;
                }
            }
        }
        return false;
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
}
