package com.rosan.ruto.ruto.observer

import android.graphics.BitmapFactory
import android.util.Base64
import com.rosan.ruto.data.AppDatabase
import com.rosan.ruto.data.model.AiModel
import com.rosan.ruto.data.model.ConversationModel
import com.rosan.ruto.data.model.MessageModel
import com.rosan.ruto.data.model.ai_model.AiType
import com.rosan.ruto.data.model.conversation.ConversationStatus
import com.rosan.ruto.data.model.message.MessageSource
import com.rosan.ruto.data.model.message.MessageType
import com.rosan.ruto.retrofit.RetrofitHttpClientBuilderFactory
import com.rosan.ruto.ruto.repo.RutoObserver
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.kotlin.model.chat.StreamingChatModelReply
import dev.langchain4j.kotlin.model.chat.chatFlow
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RutoResponder(database: AppDatabase) : RutoObserver {
    private val conversationDao = database.conversations()

    private val messageDao = database.messages()

    private val aisDao = database.ais()

    val retrofitHttpClient = RetrofitHttpClientBuilderFactory().create()

    private val aiJobs = ConcurrentHashMap<Long, Job>()

    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onInitialize(scope: CoroutineScope) {
        job = scope.launch {
            conversationDao.observeWhenStatusUpperTime(
                ConversationStatus.WAITING, updatedAt = System.currentTimeMillis()
            )
                .scan(emptyList<ConversationModel>() to emptyList<ConversationModel>()) { (oldValue, _), newValue ->
                    newValue to newValue.filter { it !in oldValue }
                }.flatMapConcat { (_, handleValue) ->
                    handleValue.asFlow()
                }.mapNotNull {
                    val messages = messageDao.all(it.id)
                    if (messages.lastOrNull()?.source != MessageSource.USER) return@mapNotNull null
                    it to messages
                }.collect { (conversation, messages) ->
                    val convId = conversation.id
                    aiJobs[convId]?.cancelAndJoin()

                    aiJobs[convId] = scope.launch {
                        try {
                            processAiResponse(conversation, messages)
                        } finally {
                            if (aiJobs[convId] == coroutineContext[Job]) aiJobs.remove(convId)
                        }
                    }
                }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
    }

    private suspend fun processAiResponse(
        conversation: ConversationModel, messages: List<MessageModel>
    ) {
        val lastUpdateAt = System.currentTimeMillis()
        conversationDao.updateStatus(
            conversation.id,
            status = ConversationStatus.RUNNING,
            updatedAt = lastUpdateAt
        )

        val aiMessage = MessageModel(
            conversationId = conversation.id,
            source = MessageSource.AI,
            type = MessageType.TEXT,
            content = ""
        )
        val aiMessageId = messageDao.add(aiMessage)
        val requestModel = buildStreamingChatModel(conversation)
        val requestMessages = buildMessages(conversation, messages)

        requestModel.chatFlow {
            messages(requestMessages)
        }.mapNotNull {
            if (it is StreamingChatModelReply.PartialResponse) it.partialResponse else null
        }.filter {
            it.isNotEmpty()
        }.onEach { chunk ->
            messageDao.chunkToLast(aiMessageId, chunk)
        }.catch { cause ->
            cause.printStackTrace()
            if (cause is CancellationException) {
                conversationDao.updateStatusSafely(
                    conversation.id,
                    status = ConversationStatus.STOPPED,
                    lastUpdateAt = lastUpdateAt
                )
                return@catch
            }
            messageDao.chunkToLast(
                aiMessageId,
                "\n${cause::class.java.name}:${cause.localizedMessage}"
            )
            messageDao.updateType(aiMessageId, MessageType.ERROR)
            conversationDao.updateStatusSafely(
                conversation.id, status = ConversationStatus.ERROR, lastUpdateAt = lastUpdateAt
            )
        }.onCompletion { cause ->
            if (cause != null) return@onCompletion
            conversationDao.updateStatusSafely(
                conversation.id, status = ConversationStatus.COMPLETED, lastUpdateAt = lastUpdateAt
            )
        }.collect()
    }

    private suspend fun buildStreamingChatModel(conversation: ConversationModel): StreamingChatModel {
        val ai = aisDao.get(conversation.aiId)
        return when (ai?.type) {
            AiType.OPENAI -> buildOpenAIStreamingChatModel(ai, conversation)
            AiType.GEMINI -> buildGeminiStreamingChatModel(ai, conversation)
            else -> throw Exception("ai not found")
        }
    }

    private fun buildOpenAIStreamingChatModel(
        ai: AiModel, conversation: ConversationModel
    ): OpenAiStreamingChatModel {
        return OpenAiStreamingChatModel.builder().baseUrl(ai.baseUrl).modelName(ai.modelId)
            .apiKey(ai.apiKey).httpClientBuilder(retrofitHttpClient).build()
    }

    private fun buildGeminiStreamingChatModel(
        ai: AiModel, conversation: ConversationModel
    ): OpenAiStreamingChatModel {
        return OpenAiStreamingChatModel.builder().baseUrl(ai.baseUrl).modelName(ai.modelId)
            .apiKey(ai.apiKey).httpClientBuilder(retrofitHttpClient).build()
    }

    private fun buildMessages(
        conversation: ConversationModel, messages: List<MessageModel>
    ): List<ChatMessage> {
        // 如果是自动任务，就启动过滤，只要最后一张图
        val lastImageIndex = if (conversation.displayId != null) {
            messages.indexOfLast {
                it.type == MessageType.IMAGE_PATH || it.type == MessageType.IMAGE_URL
            }
        } else null
        return messages.foldIndexed(mutableListOf()) { index, acc, message ->
            if (message.content.isEmpty()) return@foldIndexed acc
            val current = when (message.source to message.type) {
                MessageSource.SYSTEM to MessageType.TEXT -> SystemMessage.from(message.content)
                MessageSource.AI to MessageType.TEXT -> AiMessage.from(message.content)
                MessageSource.USER to MessageType.TEXT -> UserMessage.from(message.content)
                MessageSource.USER to MessageType.IMAGE_PATH -> {
                    if (lastImageIndex != null && lastImageIndex != index) {
                        null
                    } else {
                        val path = message.content
                        val file = File(path)
                        val bytes = file.inputStream().buffered().use { it.readBytes() }
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                        val mimeType = options.outMimeType ?: "image/jpeg"
                        val base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        UserMessage.from(ImageContent(base64String, mimeType))
                    }
                }

                MessageSource.USER to MessageType.IMAGE_URL -> {
                    if (lastImageIndex != null && lastImageIndex != index) {
                        null
                    } else UserMessage.from(ImageContent(message.content))
                }

                else -> null
            } ?: return@foldIndexed acc
            val previous = acc.lastOrNull()
            if (current is UserMessage && previous is UserMessage) {
                acc.removeLastOrNull()
                val combinedContents = previous.contents().toMutableList().apply {
                    addAll(current.contents())
                }
                acc.add(UserMessage.from(combinedContents))
            } else {
                acc.add(current)
            }
            acc
        }
    }
}