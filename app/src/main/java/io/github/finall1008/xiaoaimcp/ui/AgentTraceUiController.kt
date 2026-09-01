package io.github.finall1008.xiaoaimcp.ui

import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.finall1008.xiaoaimcp.BridgeApplication
import io.github.finall1008.xiaoaimcp.BridgeContract
import io.github.finall1008.xiaoaimcp.trace.AgentTraceContract
import io.github.finall1008.xiaoaimcp.trace.AgentTraceDatabase
import io.github.finall1008.xiaoaimcp.trace.TraceRetentionConfig
import io.github.finall1008.xiaoaimcp.trace.TraceRetentionStore
import io.github.finall1008.xiaoaimcp.trace.TraceSessionSummary
import io.github.libxposed.service.XposedService
import java.util.concurrent.Executors

internal class AgentTraceUiController(
    private val activity: ComponentActivity,
) : BridgeApplication.ServiceStateListener {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var started = false

    var sessions by mutableStateOf<List<TraceSessionSummary>>(emptyList())
        private set
    var traceEnabled by mutableStateOf(BridgeContract.DEFAULT_AGENT_TRACE_ENABLED)
        private set
    var retention by mutableStateOf(TraceRetentionConfig.defaults())
        private set
    var search by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)
    var showClearConfirmation by mutableStateOf(false)
    var preferencesReady by mutableStateOf(false)
        private set

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        activity.contentResolver.registerContentObserver(
            AgentTraceContract.CONTENT_URI,
            true,
            observer,
        )
        BridgeApplication.addServiceStateListener(this, true)
        refresh()
    }

    fun resume() {
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        activity.contentResolver.unregisterContentObserver(observer)
        BridgeApplication.removeServiceStateListener(this)
    }

    fun destroy() {
        ioExecutor.shutdownNow()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        activity.runOnUiThread(::refreshPreferences)
    }

    fun refresh() {
        refreshPreferences()
        ioExecutor.execute {
            try {
                val loaded = AgentTraceDatabase.get(activity.applicationContext).listSessions()
                activity.runOnUiThread { sessions = loaded }
            } catch (error: RuntimeException) {
                activity.runOnUiThread { errorMessage = safeMessage(error) }
            }
        }
    }

    fun updateTraceEnabled(enabled: Boolean) {
        try {
            val preferences = BridgeApplication.remotePreferences()
                ?: error("API 102 服务未连接")
            preferences.edit()
                .putBoolean(BridgeContract.PREF_AGENT_TRACE_ENABLED, enabled)
                .apply()
            traceEnabled = enabled
        } catch (error: RuntimeException) {
            errorMessage = safeMessage(error)
        }
    }

    fun openRetention() {
        activity.startActivity(Intent(activity, AgentTraceRetentionActivity::class.java))
    }

    fun openSession(summary: TraceSessionSummary) {
        activity.startActivity(Intent(activity, AgentTraceDetailActivity::class.java).apply {
            putExtra(AgentTraceContract.EXTRA_SESSION_KEY, summary.sessionKey())
        })
    }

    fun clearAll() {
        showClearConfirmation = false
        ioExecutor.execute {
            try {
                AgentTraceDatabase.get(activity.applicationContext).clearAll()
            } catch (error: RuntimeException) {
                activity.runOnUiThread { errorMessage = safeMessage(error) }
            }
        }
    }

    private fun refreshPreferences() {
        val preferences = BridgeApplication.remotePreferences()
        preferencesReady = preferences != null
        traceEnabled = preferences?.getBoolean(
            BridgeContract.PREF_AGENT_TRACE_ENABLED,
            BridgeContract.DEFAULT_AGENT_TRACE_ENABLED,
        ) ?: BridgeContract.DEFAULT_AGENT_TRACE_ENABLED
        retention = TraceRetentionStore.load(activity.applicationContext, preferences)
    }
}
