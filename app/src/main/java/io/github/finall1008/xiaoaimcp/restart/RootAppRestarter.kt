package io.github.finall1008.xiaoaimcp.restart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.finall1008.xiaoaimcp.BridgeContract
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal val ROOT_FORCE_STOP_COMMAND = listOf(
    "su",
    "-c",
    "am force-stop ${BridgeContract.TARGET_PACKAGE}",
)
internal const val ROOT_COMMAND_TIMEOUT_SECONDS = 30L
internal const val TARGET_RELAUNCH_DELAY_MILLIS = 300L

internal sealed interface RootCommandResult {
    data object Success : RootCommandResult
    data object PermissionDenied : RootCommandResult
    data object Missing : RootCommandResult
    data object TimedOut : RootCommandResult
    data object Interrupted : RootCommandResult
    data class Failed(val exitCode: Int, val output: String) : RootCommandResult
}

internal fun interface RootCommandRunner {
    fun forceStop(): RootCommandResult
}

internal fun interface TargetLauncher {
    fun launch(): String?
}

internal fun interface RestartDelay {
    fun await()
}

internal fun interface RestartStateListener {
    fun onRestartStateChanged(inFlight: Boolean, errorMessage: String?)
}

internal fun classifyRootCommandResult(exitCode: Int, output: String): RootCommandResult {
    val detail = output.trim()
    return when {
        exitCode == 0 -> RootCommandResult.Success
        (exitCode == 1 && detail.isBlank()) ||
            detail.contains("denied", ignoreCase = true) ||
            detail.contains("not allowed", ignoreCase = true) ||
            detail.contains("permission", ignoreCase = true) -> RootCommandResult.PermissionDenied
        else -> RootCommandResult.Failed(exitCode, detail.take(300))
    }
}

internal class ProcessRootCommandRunner : RootCommandRunner {
    override fun forceStop(): RootCommandResult {
        val process = try {
            ProcessBuilder(ROOT_FORCE_STOP_COMMAND)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return RootCommandResult.Missing
        }
        return try {
            if (!process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                RootCommandResult.TimedOut
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                classifyRootCommandResult(process.exitValue(), output)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            RootCommandResult.Interrupted
        }
    }
}

internal class RootAppRestarter(
    private val commandRunner: RootCommandRunner,
    private val delay: RestartDelay,
    private val executor: Executor,
) {
    private val inFlight = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<RestartStateListener>()

    @Volatile
    private var lastError: String? = null

    fun addListener(listener: RestartStateListener) {
        listeners.add(listener)
        listener.onRestartStateChanged(inFlight.get(), lastError)
    }

    fun removeListener(listener: RestartStateListener) {
        listeners.remove(listener)
    }

    fun clearError() {
        lastError = null
    }

    fun start(launcher: TargetLauncher): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        lastError = null
        notifyListeners()
        try {
            executor.execute {
                val error = try {
                    when (val result = commandRunner.forceStop()) {
                        RootCommandResult.Success -> relaunch(launcher)
                        RootCommandResult.PermissionDenied -> "root 授权被拒绝，无法重启超级小爱"
                        RootCommandResult.Missing -> "未找到 su，设备未提供可用的 root 环境"
                        RootCommandResult.TimedOut -> "等待 root 授权或命令执行超时"
                        RootCommandResult.Interrupted -> "root 重启任务被中断"
                        is RootCommandResult.Failed -> buildString {
                            append("root 命令执行失败（退出码 ${result.exitCode}）")
                            if (result.output.isNotBlank()) append("：${result.output}")
                        }
                    }
                } catch (error: RuntimeException) {
                    "root 重启任务异常：${error.message ?: error.javaClass.simpleName}"
                }
                lastError = error
                inFlight.set(false)
                notifyListeners()
            }
        } catch (error: RuntimeException) {
            lastError = "无法启动 root 重启任务：${error.message ?: error.javaClass.simpleName}"
            inFlight.set(false)
            notifyListeners()
        }
        return true
    }

    private fun relaunch(launcher: TargetLauncher): String? {
        return try {
            delay.await()
            launcher.launch()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "重启等待被中断，超级小爱未重新打开"
        } catch (error: RuntimeException) {
            "无法重新打开超级小爱：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun notifyListeners() {
        val running = inFlight.get()
        val error = lastError
        listeners.forEach { it.onRestartStateChanged(running, error) }
    }
}

internal object XiaoAiRootRestarter {
    private val coordinator = RootAppRestarter(
        commandRunner = ProcessRootCommandRunner(),
        delay = RestartDelay { Thread.sleep(TARGET_RELAUNCH_DELAY_MILLIS) },
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "xiaoai-root-restart")
        },
    )

    fun addListener(listener: RestartStateListener) = coordinator.addListener(listener)

    fun removeListener(listener: RestartStateListener) = coordinator.removeListener(listener)

    fun clearError() = coordinator.clearError()

    fun restart(context: Context): Boolean {
        val appContext = context.applicationContext
        return coordinator.start(TargetLauncher { launchXiaoAi(appContext) })
    }
}

private fun launchXiaoAi(context: Context): String? {
    val launchIntent = Intent().setComponent(
        ComponentName(
            BridgeContract.TARGET_PACKAGE,
            BridgeContract.TARGET_LAUNCH_ACTIVITY,
        ),
    )
    return try {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
        null
    } catch (error: RuntimeException) {
        "无法重新打开超级小爱：${error.message ?: error.javaClass.simpleName}"
    }
}
