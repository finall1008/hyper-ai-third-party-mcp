package io.github.finall1008.xiaoaimcp.trace

import org.json.JSONArray
import org.json.JSONObject

internal enum class TraceCardKind(val label: String) {
    SYSTEM("SYSTEM"),
    USER("USER"),
    ASSISTANT("ASSISTANT"),
    TOOL("TOOL"),
    CONTROL("EVENT"),
    ERROR("ERROR"),
}

internal data class TraceTimelineCard(
    val id: String,
    val executionId: String,
    val turnIndex: Int,
    val kind: TraceCardKind,
    val title: String,
    val summary: String,
    val detail: String,
    val rawJson: String,
    val observedAt: Long,
    val status: String? = null,
)

internal object TraceTimelineProjector {
    fun project(detail: TraceSessionDetail): List<TraceTimelineCard> {
        val turns = detail.turns().associateBy { it.executionId() }
        val cards = mutableListOf<TraceTimelineCard>()
        detail.turns().forEach { turn ->
            cards += systemCard(turn)
            cards += userCard(turn)
        }

        val streams = linkedMapOf<String, StreamAccumulator>()
        val tools = linkedMapOf<String, ToolAccumulator>()
        detail.events().forEach { event ->
            val turn = turns[event.executionId()] ?: return@forEach
            val payload = jsonObject(event.payloadJson())
            when (event.eventType()) {
                "STREAM_DELTA", "TEXT_COMPLETED", "REASONING_COMPLETED" -> {
                    val streamId = payload.optNullableString("streamId")
                        ?: "event-${event.databaseId()}"
                    val key = "${event.executionId()}\u0000$streamId"
                    streams.getOrPut(key) {
                        StreamAccumulator(event.executionId(), turn.turnIndex(), streamId)
                    }.accept(event, payload)
                }
                "TOOL_CALL_DETECTED", "TOOL_EXECUTING", "TOOL_PROGRESS",
                "TOOL_COMPLETED", "TOOL_RESULT_READY", "TOOL_FAILED" -> {
                    val callId = payload.optNullableString("toolCallId")
                        ?: payload.optNullableString("id")
                        ?: "${payload.optString("toolName", payload.optString("name", "tool"))}"
                    val key = "${event.executionId()}\u0000$callId"
                    tools.getOrPut(key) {
                        ToolAccumulator(event.executionId(), turn.turnIndex(), callId)
                    }.accept(event, payload)
                }
                else -> cards += eventCard(turn, event, payload)
            }
        }
        cards += streams.values.map { it.card() }
        cards += tools.values.map { it.card() }
        return cards.sortedWith(
            compareBy<TraceTimelineCard> { it.turnIndex }
                .thenBy { it.observedAt }
                .thenBy { it.id },
        )
    }

    private fun systemCard(turn: TraceTurnRecord): TraceTimelineCard {
        val tools = prettyJson(turn.toolCatalogJson())
        val options = prettyJson(turn.executionOptionsJson())
        val detail = buildString {
            append(turn.systemPrompt().ifBlank { "宿主未提供 System Prompt" })
            append("\n\n===== Tools =====\n")
            append(tools)
            append("\n\n===== Execution Options =====\n")
            append(options)
        }
        val raw = JSONObject().apply {
            put("systemPrompt", turn.systemPrompt())
            put("tools", parseJson(turn.toolCatalogJson()))
            put("executionOptions", parseJson(turn.executionOptionsJson()))
        }.toString(2)
        return TraceTimelineCard(
            id = "${turn.executionId()}:system",
            executionId = turn.executionId(),
            turnIndex = turn.turnIndex(),
            kind = TraceCardKind.SYSTEM,
            title = "System Prompt",
            summary = "${turn.systemPrompt().length} 字符 · ${jsonArraySize(turn.toolCatalogJson())} 个工具",
            detail = detail,
            rawJson = raw,
            observedAt = turn.startedAt(),
            status = turn.status(),
        )
    }

    private fun userCard(turn: TraceTurnRecord): TraceTimelineCard {
        val input = jsonObject(turn.userInputJson())
        val text = input.optNullableString("text").orEmpty()
        return TraceTimelineCard(
            id = "${turn.executionId()}:user",
            executionId = turn.executionId(),
            turnIndex = turn.turnIndex(),
            kind = TraceCardKind.USER,
            title = "User Prompt",
            summary = text.preview(),
            detail = prettyJson(turn.userInputJson()),
            rawJson = prettyJson(turn.userInputJson()),
            observedAt = turn.startedAt() + 1,
            status = turn.status(),
        )
    }

    private fun eventCard(
        turn: TraceTurnRecord,
        event: TraceEventRecord,
        payload: JSONObject,
    ): TraceTimelineCard {
        val kind = when (event.eventType()) {
            "ERROR", "CAPTURE_ERROR", "GAP" -> TraceCardKind.ERROR
            "ASSISTANT_MESSAGE_READY" -> TraceCardKind.ASSISTANT
            else -> TraceCardKind.CONTROL
        }
        val summary = when (event.eventType()) {
            "SESSION_STARTED" -> payload.optNullableString("sessionId") ?: "会话开始"
            "ITERATION_STARTED" -> "第 ${payload.optInt("iteration", 0)} 步"
            "ROUTE_SELECTED" -> payload.optNullableString("target") ?: "已选择模型路由"
            "LLM_RETRYING" -> "第 ${payload.optInt("retryIndex", 0)} 次重试"
            "LLM_FALLBACK" -> listOfNotNull(
                payload.optNullableString("fromModel"),
                payload.optNullableString("toModel"),
            ).joinToString(" → ")
            "ERROR" -> payload.optNullableString("message") ?: "Agent 执行失败"
            "GAP" -> "有 ${payload.optLong("dropped_records", 1)} 条记录未写入"
            "COMPLETED" -> "Agent 执行完成"
            else -> payload.optNullableString("message")
                ?: payload.optNullableString("reason")
                ?: event.eventType()
        }
        return TraceTimelineCard(
            id = "event:${event.databaseId()}",
            executionId = event.executionId(),
            turnIndex = turn.turnIndex(),
            kind = kind,
            title = event.eventType().replace('_', ' '),
            summary = summary.preview(),
            detail = prettyJson(event.payloadJson()),
            rawJson = prettyJson(event.rawJson()),
            observedAt = event.observedAt(),
            status = if (kind == TraceCardKind.ERROR) "ERROR" else null,
        )
    }

    private class StreamAccumulator(
        private val executionId: String,
        private val turnIndex: Int,
        private val streamId: String,
    ) {
        private val deltas = StringBuilder()
        private val raw = JSONArray()
        private var finalText: String? = null
        private var reasoning = false
        private var firstAt = Long.MAX_VALUE
        private var completedAt: Long? = null

        fun accept(event: TraceEventRecord, payload: JSONObject) {
            firstAt = minOf(firstAt, event.observedAt())
            raw.put(parseJson(event.rawJson()))
            when (event.eventType()) {
                "STREAM_DELTA" -> deltas.append(payload.optString("text", ""))
                "TEXT_COMPLETED" -> {
                    finalText = payload.optString("fullText", deltas.toString())
                    reasoning = false
                    completedAt = event.observedAt()
                }
                "REASONING_COMPLETED" -> {
                    finalText = payload.optString("fullText", deltas.toString())
                    reasoning = true
                    completedAt = event.observedAt()
                }
            }
        }

        fun card(): TraceTimelineCard {
            val text = finalText ?: deltas.toString()
            val title = when {
                completedAt == null -> "流式输出"
                reasoning -> "Reasoning"
                else -> "Assistant Output"
            }
            return TraceTimelineCard(
                id = "$executionId:stream:$streamId",
                executionId = executionId,
                turnIndex = turnIndex,
                kind = TraceCardKind.ASSISTANT,
                title = title,
                summary = text.preview(),
                detail = text,
                rawJson = raw.toString(2),
                observedAt = if (firstAt == Long.MAX_VALUE) 0 else firstAt,
                status = if (completedAt == null) "RUNNING" else "COMPLETED",
            )
        }
    }

    private class ToolAccumulator(
        private val executionId: String,
        private val turnIndex: Int,
        private val callId: String,
    ) {
        private val raw = JSONArray()
        private var name = "未知工具"
        private var arguments: String? = null
        private var result: String? = null
        private var error: String? = null
        private var metadata: Any? = null
        private var duration: Long? = null
        private var status = "PENDING"
        private var firstAt = Long.MAX_VALUE

        fun accept(event: TraceEventRecord, payload: JSONObject) {
            firstAt = minOf(firstAt, event.observedAt())
            raw.put(parseJson(event.rawJson()))
            name = payload.optNullableString("toolName")
                ?: payload.optNullableString("name")
                ?: name
            arguments = payload.optNullableString("arguments") ?: arguments
            result = payload.optNullableString("result") ?: result
            error = payload.optNullableString("error") ?: error
            if (payload.has("metadata") && !payload.isNull("metadata")) {
                metadata = payload.opt("metadata")
            }
            if (payload.has("durationMs") && !payload.isNull("durationMs")) {
                duration = payload.optLong("durationMs")
            }
            status = when (event.eventType()) {
                "TOOL_EXECUTING" -> "RUNNING"
                "TOOL_COMPLETED" -> "COMPLETED"
                "TOOL_RESULT_READY" -> if (payload.optBoolean("success", true)) {
                    "COMPLETED"
                } else {
                    "ERROR"
                }
                "TOOL_FAILED" -> "ERROR"
                else -> status
            }
        }

        fun card(): TraceTimelineCard {
            val detail = buildString {
                append("调用 ID: ").append(callId)
                append("\n状态: ").append(status)
                append("\n\n输入:\n").append(arguments ?: "宿主未提供")
                append("\n\n输出:\n").append(result ?: "宿主未提供")
                if (error != null) append("\n\n错误:\n").append(error)
                if (duration != null) append("\n\n耗时: ").append(duration).append(" ms")
                if (metadata != null) {
                    append("\n\n元数据:\n").append(prettyJson(metadata.toString()))
                }
            }
            return TraceTimelineCard(
                id = "$executionId:tool:$callId",
                executionId = executionId,
                turnIndex = turnIndex,
                kind = TraceCardKind.TOOL,
                title = name,
                summary = when {
                    error != null -> error.orEmpty().preview()
                    result != null -> result.orEmpty().preview()
                    else -> arguments.orEmpty().preview()
                },
                detail = detail,
                rawJson = raw.toString(2),
                observedAt = if (firstAt == Long.MAX_VALUE) 0 else firstAt,
                status = status,
            )
        }
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeUnless { it.isBlank() }
}

private fun String.preview(max: Int = 120): String {
    val compact = replace(Regex("\\s+"), " ").trim()
    return when {
        compact.isEmpty() -> "无正文"
        compact.length <= max -> compact
        else -> compact.take(max) + "…"
    }
}

internal fun prettyJson(value: String): String {
    return try {
        when {
            value.trimStart().startsWith("{") -> JSONObject(value).toString(2)
            value.trimStart().startsWith("[") -> JSONArray(value).toString(2)
            else -> value
        }
    } catch (_: Exception) {
        value
    }
}

private fun parseJson(value: String): Any {
    return try {
        when {
            value.trimStart().startsWith("{") -> JSONObject(value)
            value.trimStart().startsWith("[") -> JSONArray(value)
            else -> value
        }
    } catch (_: Exception) {
        value
    }
}

private fun jsonObject(value: String): JSONObject {
    return try {
        JSONObject(value)
    } catch (_: Exception) {
        JSONObject()
    }
}

private fun jsonArraySize(value: String): Int {
    return try {
        JSONArray(value).length()
    } catch (_: Exception) {
        0
    }
}
