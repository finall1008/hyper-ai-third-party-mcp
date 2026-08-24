package io.github.finall1008.xiaoaimcp.filepolicy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class PathPolicyEvaluatorTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void longestDirectoryRuleWinsAndBackgroundRequiresExplicitGrant() throws Exception {
        File root = temporary.newFolder("shared");
        File child = new File(root, "Download");
        assertTrue(child.mkdir());
        File target = new File(child, "item.txt");
        FileAccessRule broad = new FileAccessRule(root.getPath(), true,
                false, false, false, false);
        FileAccessRule narrow = new FileAccessRule(child.getPath(), true,
                true, true, true, false);
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(broad, narrow));

        assertSame(narrow, PathPolicyEvaluator.matchingRule(config, target));
        assertTrue(PathPolicyEvaluator.canMutate(config, target, "osbot.main::bg/timer/id"));
        assertTrue(PathPolicyEvaluator.canLockscreenMutate(config, target.getPath()));
        assertFalse(PathPolicyEvaluator.canRecursiveDelete(config, target.getPath()));
    }

    @Test
    public void explicitNarrowDenyOverridesBroadRule() throws Exception {
        File root = temporary.newFolder("sdcard");
        File privateDirectory = new File(root, "Private");
        assertTrue(privateDirectory.mkdirs());
        FileAccessRule broad = new FileAccessRule(root.getPath(), true,
                true, true, true, true);
        FileAccessRule narrowDeny = new FileAccessRule(privateDirectory.getPath(), false,
                false, false, false, false);
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(broad, narrowDeny));

        assertFalse(PathPolicyEvaluator.canMutate(config,
                new File(privateDirectory, "state.db"), "osbot.main"));
    }

    @Test
    public void configuredRuleIsNotOverriddenByModulePathBlacklist() throws Exception {
        File root = temporary.newFolder("storage");
        File androidData = new File(root, "Android/data/package");
        assertTrue(androidData.mkdirs());
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(
                new FileAccessRule(root.getPath(), true,
                        true, true, true, true)
        ));

        assertTrue(PathPolicyEvaluator.canMutate(config,
                new File(androidData, "state.db"), "osbot.main"));
    }

    @Test
    public void lockscreenCliRequiresAuthorizedAbsolutePathsAndBlocksExec() throws Exception {
        File root = temporary.newFolder("documents");
        FileAccessRule rule = new FileAccessRule(root.getPath(), true,
                true, true, false, false);
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(rule));
        String file = new File(root, "a.txt").getPath();

        assertTrue(LockscreenFileAccessEvaluator.isCliCommandAllowed(
                config, "sed", List.of("-i", "s/a/b/", file)));
        assertFalse(LockscreenFileAccessEvaluator.isCliCommandAllowed(
                config, "find", List.of(root.getPath(), "-exec", "rm", "{}", ";")));
        assertFalse(LockscreenFileAccessEvaluator.isCliCommandAllowed(
                config, "rm", List.of("-r", root.getPath())));
        assertFalse(LockscreenFileAccessEvaluator.isCliCommandAllowed(
                config, "find", List.of(root.getPath(), "-delete")));
        assertTrue(LockscreenFileAccessEvaluator.isDirectToolAllowed(
                config, "read_file", Map.of("path", file)));
    }

    @Test
    public void lockscreenCopyRequiresReadableSourceAndMutableDestination() throws Exception {
        File sourceRoot = temporary.newFolder("source");
        File destinationRoot = temporary.newFolder("destination");
        String source = new File(sourceRoot, "input.txt").getPath();
        String destination = new File(destinationRoot, "output.txt").getPath();
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(
                new FileAccessRule(sourceRoot.getPath(), false,
                        true, false, false, false),
                new FileAccessRule(destinationRoot.getPath(), true,
                        false, true, false, false)
        ));

        assertTrue(LockscreenFileAccessEvaluator.isDirectToolAllowed(
                config, "copy_file", Map.of(
                        "source", source,
                        "destination", destination
                )));
        assertTrue(LockscreenFileAccessEvaluator.isCliCommandAllowed(
                config, "cp", List.of(source, destination)));
        assertFalse(LockscreenFileAccessEvaluator.isDirectToolAllowed(
                config, "move_file", Map.of(
                        "source", source,
                        "destination", destination
                )));
    }

    @Test
    public void confirmationPolicyDistinguishesBackgroundAndForeground() throws Exception {
        File root = temporary.newFolder("automatic");
        String file = new File(root, "item.txt").getPath();
        FileAccessRule backgroundAutomatic = new FileAccessRule(root.getPath(), true,
                false, false, true, false,
                MutationConfirmationPolicy.BACKGROUND_AUTOMATIC);
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(backgroundAutomatic));

        assertTrue(PathPolicyEvaluator.canSkipMutationConfirmation(
                config, List.of(file), "osbot.main::bg/timer/id"));
        assertTrue(PathPolicyEvaluator.canSkipMutationConfirmation(
                config, List.of(file), "osbot.main::timer-session"));
        assertTrue(PathPolicyEvaluator.canSkipMutationConfirmation(
                config, List.of("/home/source.txt", file),
                "osbot.main::bg/timer/id"));
        assertFalse(PathPolicyEvaluator.canSkipMutationConfirmation(
                config, List.of(file), "osbot.main"));

        FileAccessRule allAutomatic = new FileAccessRule(root.getPath(), true,
                false, false, true, false,
                MutationConfirmationPolicy.ALL_AGENTS_AUTOMATIC);
        config = new FilePolicyConfig(true, List.of(allAutomatic));
        assertTrue(PathPolicyEvaluator.canSkipMutationConfirmation(
                config, List.of(file), "osbot.main"));
    }

    @Test
    public void automaticConfirmationRequiresEveryExternalPath()
            throws Exception {
        File root = temporary.newFolder("authorized");
        File outside = temporary.newFolder("outside");
        File directory = new File(root, "folder");
        assertTrue(directory.mkdir());
        FileAccessRule rule = new FileAccessRule(root.getPath(), true,
                false, false, true, false,
                MutationConfirmationPolicy.ALL_AGENTS_AUTOMATIC);
        FilePolicyConfig config = new FilePolicyConfig(true, List.of(rule));

        assertFalse(PathPolicyEvaluator.canSkipMutationConfirmation(config,
                List.of(new File(root, "a.txt").getPath(),
                        new File(outside, "b.txt").getPath()), "osbot.main"));
        assertTrue(PathPolicyEvaluator.canSkipMutationConfirmation(config,
                List.of(directory.getPath()), "osbot.main"));
    }
}
