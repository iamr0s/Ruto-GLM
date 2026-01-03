package com.rosan.ruto.retrofit

import dev.langchain4j.http.client.sse.DefaultServerSentEventParsingHandle
import dev.langchain4j.http.client.sse.ServerSentEvent
import dev.langchain4j.http.client.sse.ServerSentEventContext
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.http.client.sse.ServerSentEventParser
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8

/**
 * 优化后的 Langchain4j SSE 解析器
 */
object Langchain4jServerSentEventParser : ServerSentEventParser {

    override fun parse(httpResponseBody: InputStream, listener: ServerSentEventListener?) {
        val parsingHandle = DefaultServerSentEventParsingHandle(httpResponseBody)
        val context = ServerSentEventContext(parsingHandle)

        try {
            httpResponseBody.bufferedReader(UTF_8).use { reader ->
                var eventType: String? = null
                val dataBuffer = StringBuilder()

                // 使用 lineSequence 配合 takeWhile 处理取消逻辑
                reader.lineSequence()
                    .takeWhile { !parsingHandle.isCancelled }
                    .forEach { line ->
                        when {
                            line.isEmpty() -> {
                                if (dataBuffer.isNotEmpty()) {
                                    dispatch(listener, eventType, dataBuffer.toString(), context)
                                    // 重置状态
                                    eventType = null
                                    dataBuffer.setLength(0)
                                }
                            }

                            line.startsWith("event:") -> {
                                eventType = line.removePrefix("event:").trim()
                            }

                            line.startsWith("data:") -> {
                                val content = line.removePrefix("data:").removePrefix(" ")
                                if (dataBuffer.isNotEmpty()) dataBuffer.append("\n")
                                dataBuffer.append(content)
                            }
                        }
                    }

                // 处理流结束后可能遗留的最后一条数据
                if (!parsingHandle.isCancelled && dataBuffer.isNotEmpty()) {
                    dispatch(listener, eventType, dataBuffer.toString(), context)
                }
            }
        } catch (e: Exception) {
            // 如果解析过程出错，通知 listener
            runIgnoringExceptions { listener?.onError(e) }
        }
    }

    private fun dispatch(
        listener: ServerSentEventListener?,
        event: String?,
        data: String,
        context: ServerSentEventContext
    ) {
        // 使用 ?. 执行空安全调用，避免 if (listener != null)
        runIgnoringExceptions {
            listener?.onEvent(ServerSentEvent(event, data), context)
        }
    }

    private inline fun runIgnoringExceptions(block: () -> Unit) {
        runCatching { block() } // Kotlin 内置的更现代的 try-catch 糖
    }
}