package com.rosan.ruto.ruto.observer

import android.content.Context
import com.rosan.installer.ext.util.toast
import com.rosan.ruto.data.AppDatabase
import com.rosan.ruto.data.model.ConversationModel
import com.rosan.ruto.data.model.MessageModel
import com.rosan.ruto.data.model.conversation.ConversationStatus
import com.rosan.ruto.data.model.message.MessageSource
import com.rosan.ruto.data.model.message.MessageType
import com.rosan.ruto.device.repo.DeviceRepo
import com.rosan.ruto.ruto.DefaultRutoRuntime
import com.rosan.ruto.ruto.GLMCommandParser
import com.rosan.ruto.ruto.repo.RutoObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class RutoAiTasker(
    private val context: Context, database: AppDatabase, private val device: DeviceRepo
) : RutoObserver {
    private val conversationDao = database.conversations()

    private val messageDao = database.messages()

    private val aisDao = database.ais()

    private val aiJobs = ConcurrentHashMap<Long, Job>()

    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onInitialize(scope: CoroutineScope) {
        job = scope.launch {
            conversationDao.observeWhenStatusUpperTime(
                ConversationStatus.COMPLETED, updatedAt = System.currentTimeMillis()
            )
                .scan(emptyList<ConversationModel>() to emptyList<ConversationModel>()) { (oldValue, _), newValue ->
                    newValue to newValue.filter { it !in oldValue }
                }.flatMapConcat { (_, handleValue) ->
                    handleValue.asFlow()
                }.mapNotNull {
                    val displayId = it.displayId ?: return@mapNotNull null
                    displayId to it
                }.collect { (displayId, conversation) ->
                    val convId = conversation.id
                    aiJobs[convId]?.cancelAndJoin()

                    aiJobs[convId] = scope.launch {
                        try {
                            processAiRequest(displayId, conversation)
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

    private suspend fun processAiRequest(
        displayId: Int, conversation: ConversationModel
    ) {
        val messages = messageDao.all(conversation.id)
        val lastMessage = messages.lastOrNull()
        if (lastMessage == null) processAiFirstRequest(conversation)
        if (lastMessage == null || (lastMessage.source == MessageSource.USER && lastMessage.type !in arrayOf(
                MessageType.IMAGE_PATH, MessageType.IMAGE_URL
            ))
        ) {
            processAiCaptureRequest(displayId, conversation)
        } else if (lastMessage.source == MessageSource.AI && lastMessage.type == MessageType.TEXT) {
            processAiFunction(displayId, conversation, lastMessage)
        }
    }

    private suspend fun processAiFirstRequest(
        conversation: ConversationModel
    ) {
        if (!conversation.isGLMPhone) return
        val system =
            context.assets.open("prompts/glm_phone.txt").bufferedReader().use { it.readText() }
        messageDao.add(
            MessageModel(
                conversationId = conversation.id, source = MessageSource.SYSTEM, content = system
            )
        )
        messageDao.add(
            MessageModel(
                conversationId = conversation.id,
                source = MessageSource.USER,
                content = conversation.name
            )
        )
    }

    private suspend fun processAiCaptureRequest(
        displayId: Int, conversation: ConversationModel
    ) {
        try {
            val bitmap = device.displayManager.capture(displayId).bitmap
            messageDao.addImage(conversation.id, bitmap)
            conversationDao.updateStatus(conversation.id, ConversationStatus.WAITING)
        } catch (e: Exception) {
            e.printStackTrace()
            conversationDao.updateStatus(conversation.id, ConversationStatus.ERROR)
        }
    }

    private suspend fun processAiFunction(
        displayId: Int, conversation: ConversationModel, message: MessageModel
    ) {
        val response = message.content
        val parsed = GLMCommandParser.parse(response)
        if (parsed !is GLMCommandParser.Status.Completed) return
        if (parsed.command.mapping == "finish") {
            context.toast("已完成：" + conversation.name)
            return
        }
        val runtime = DefaultRutoRuntime(device, displayId)
        try {
            parsed.callFunction(runtime)
            delay(1000)
            processAiCaptureRequest(displayId, conversation)
        } catch (e: Exception) {
            e.printStackTrace()
            conversationDao.updateStatus(conversation.id, ConversationStatus.ERROR)
        }
    }
}