package io.github.finall1008.xiaoaimcp.filepolicy;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class PathPolicyEvaluator {
    private PathPolicyEvaluator() {
    }

    public static FileAccessRule matchingRule(FilePolicyConfig config, String rawPath) {
        if (config == null || !config.enabled() || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String path = stripQuery(rawPath.trim());
        if (path.startsWith("content://") || path.startsWith("miclaw://")) {
            return null;
        }
        try {
            return matchingRule(config, new File(path));
        } catch (IOException ignored) {
            return null;
        }
    }

    public static FileAccessRule matchingRule(FilePolicyConfig config, File target)
            throws IOException {
        if (config == null || !config.enabled() || target == null) {
            return null;
        }
        String canonicalTarget = target.getCanonicalPath();
        FileAccessRule best = null;
        int bestLength = -1;
        String bestCanonicalRoot = null;
        for (FileAccessRule rule : config.rules()) {
            File rootFile = new File(rule.path());
            if (!rootFile.exists() || !rootFile.isDirectory()) {
                continue;
            }
            String canonicalRoot = rootFile.getCanonicalPath();
            if (!isInside(canonicalTarget, canonicalRoot)) {
                continue;
            }
            if (canonicalRoot.length() > bestLength) {
                best = rule;
                bestLength = canonicalRoot.length();
                bestCanonicalRoot = canonicalRoot;
            } else if (canonicalRoot.equals(bestCanonicalRoot)) {
                // Equivalent aliases with conflicting rules must never become order-dependent.
                return null;
            }
        }
        return best;
    }

    public static boolean canMutate(
            FilePolicyConfig config,
            File target,
            String agentId
    ) throws IOException {
        FileAccessRule rule = matchingRule(config, target);
        if (rule == null || !rule.allowMutation()) {
            return false;
        }
        return !isBackgroundAgent(agentId) || rule.allowBackgroundMutation();
    }

    public static boolean canLockscreenRead(FilePolicyConfig config, String path) {
        FileAccessRule rule = matchingRule(config, path);
        return rule != null && rule.allowLockscreenRead();
    }

    public static boolean canLockscreenMutate(FilePolicyConfig config, String path) {
        FileAccessRule rule = matchingRule(config, path);
        return rule != null && rule.allowMutation() && rule.allowLockscreenMutation();
    }

    public static boolean canRecursiveDelete(FilePolicyConfig config, String path) {
        FileAccessRule rule = matchingRule(config, path);
        return rule != null
                && rule.allowMutation()
                && rule.allowLockscreenMutation()
                && rule.allowRecursiveDelete();
    }

    public static boolean canSkipMutationConfirmation(
            FilePolicyConfig config,
            List<String> paths,
            String agentId
    ) {
        if (config == null || !config.enabled() || paths == null || paths.isEmpty()) {
            return false;
        }
        boolean background = isBackgroundAgent(agentId);
        boolean matchedExternalPath = false;
        for (String path : paths) {
            FileAccessRule rule = matchingRule(config, path);
            if (rule == null) {
                if (isHostManagedVirtualPath(path)) {
                    continue;
                }
                return false;
            }
            matchedExternalPath = true;
            if (!rule.allowMutation()
                    || background && !rule.allowBackgroundMutation()
                    || !rule.confirmationPolicy().automaticallyAllows(agentId)) {
                return false;
            }
        }
        return matchedExternalPath;
    }

    public static boolean isBackgroundAgent(String agentId) {
        if (agentId == null) {
            return false;
        }
        return agentId.equals("timer-session")
                || agentId.startsWith("bg/")
                || agentId.contains("::bg/")
                || agentId.endsWith("::timer-session")
                || agentId.equals("background_task");
    }

    static boolean isInside(String target, String root) {
        return target.equals(root) || target.startsWith(root + File.separator);
    }

    private static String stripQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static boolean isHostManagedVirtualPath(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String path = stripQuery(rawPath.trim());
        if (path.isEmpty()
                || path.startsWith("content://")
                || path.startsWith("miclaw://")
                || path.startsWith("~")
                || !path.startsWith("/")) {
            return true;
        }
        return isVirtualRoot(path, "/home")
                || isVirtualRoot(path, "/memory")
                || isVirtualRoot(path, "/tmp")
                || isVirtualRoot(path, "/downloads")
                || isVirtualRoot(path, "/skills")
                || isVirtualRoot(path, "/chat_images");
    }

    private static boolean isVirtualRoot(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
