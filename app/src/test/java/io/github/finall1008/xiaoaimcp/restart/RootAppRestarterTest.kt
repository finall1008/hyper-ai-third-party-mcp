package io.github.finall1008.xiaoaimcp.restart

import io.github.finall1008.xiaoaimcp.BridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class RootAppRestarterTest {
    @Test
    fun usesFixedForceStopCommandAndTimeout() {
        assertEquals(
            listOf("su", "-c", "am force-stop com.miui.voiceassist"),
            ROOT_FORCE_STOP_COMMAND,
        )
        assertEquals(30L, ROOT_COMMAND_TIMEOUT_SECONDS)
        assertEquals(300L, TARGET_RELAUNCH_DELAY_MILLIS)
        assertEquals(
            "com.xiaomi.voiceassistant.LaunchHomeRouterActivity",
            BridgeContract.TARGET_LAUNCH_ACTIVITY,
        )
    }

    @Test
    fun classifiesProcessExitResults() {
        assertEquals(RootCommandResult.Success, classifyRootCommandResult(0, ""))
        assertEquals(RootCommandResult.PermissionDenied, classifyRootCommandResult(1, ""))
        assertEquals(
            RootCommandResult.PermissionDenied,
            classifyRootCommandResult(1, "Permission denied"),
        )
        assertEquals(
            RootCommandResult.Failed(9, "unexpected failure"),
            classifyRootCommandResult(9, " unexpected failure "),
        )
    }

    @Test
    fun successfulRootCommandRelaunchesTarget() {
        var delayCount = 0
        var launchCount = 0
        val states = mutableListOf<Pair<Boolean, String?>>()
        val restarter = restarter(
            result = RootCommandResult.Success,
            delay = { delayCount++ },
        )
        restarter.addListener { running, error -> states += running to error }

        assertTrue(restarter.start { launchCount++; null })

        assertEquals(1, delayCount)
        assertEquals(1, launchCount)
        assertEquals(listOf(false to null, true to null, false to null), states)
    }

    @Test
    fun reportsRootPermissionDenial() {
        assertFailure(
            RootCommandResult.PermissionDenied,
            "root 授权被拒绝，无法重启超级小爱",
        )
    }

    @Test
    fun reportsMissingRootEnvironment() {
        assertFailure(
            RootCommandResult.Missing,
            "未找到 su，设备未提供可用的 root 环境",
        )
    }

    @Test
    fun reportsNonZeroExitAndOutput() {
        assertFailure(
            RootCommandResult.Failed(7, "force-stop failed"),
            "root 命令执行失败（退出码 7）：force-stop failed",
        )
    }

    @Test
    fun reportsRootCommandTimeout() {
        assertFailure(
            RootCommandResult.TimedOut,
            "等待 root 授权或命令执行超时",
        )
    }

    @Test
    fun reportsMissingLaunchIntentAfterSuccessfulStop() {
        val errors = mutableListOf<String?>()
        val restarter = restarter(RootCommandResult.Success)
        restarter.addListener { _, error -> errors += error }

        assertTrue(restarter.start { "无法取得超级小爱的启动入口" })

        assertEquals("无法取得超级小爱的启动入口", errors.last())
    }

    @Test
    fun rejectsDuplicateRequestsWhileFirstIsQueued() {
        val executor = QueueExecutor()
        val restarter = RootAppRestarter(
            commandRunner = RootCommandRunner { RootCommandResult.Success },
            delay = RestartDelay {},
            executor = executor,
        )

        assertTrue(restarter.start { null })
        assertFalse(restarter.start { null })
        assertEquals(1, executor.tasks.size)

        executor.runNext()
        assertTrue(restarter.start { null })
    }

    @Test
    fun replacementListenerReceivesInFlightAndCompletionState() {
        val executor = QueueExecutor()
        val restarter = RootAppRestarter(
            commandRunner = RootCommandRunner { RootCommandResult.Success },
            delay = RestartDelay {},
            executor = executor,
        )
        val firstListener = RestartStateListener { _, _ -> }
        restarter.addListener(firstListener)
        assertTrue(restarter.start { null })
        restarter.removeListener(firstListener)

        val replacementStates = mutableListOf<Pair<Boolean, String?>>()
        restarter.addListener { running, error -> replacementStates += running to error }
        executor.runNext()

        assertEquals(listOf(true to null, false to null), replacementStates)
    }

    private fun assertFailure(result: RootCommandResult, expectedMessage: String) {
        var launchCount = 0
        var finalError: String? = null
        val restarter = restarter(result)
        restarter.addListener { running, error ->
            if (!running) finalError = error
        }

        assertTrue(restarter.start { launchCount++; null })

        assertEquals(0, launchCount)
        assertEquals(expectedMessage, finalError)
        restarter.clearError()
        finalError = null
        restarter.addListener { _, error -> finalError = error }
        assertNull(finalError)
    }

    private fun restarter(
        result: RootCommandResult,
        delay: () -> Unit = {},
    ): RootAppRestarter {
        return RootAppRestarter(
            commandRunner = RootCommandRunner { result },
            delay = RestartDelay(delay),
            executor = Executor { it.run() },
        )
    }

    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
