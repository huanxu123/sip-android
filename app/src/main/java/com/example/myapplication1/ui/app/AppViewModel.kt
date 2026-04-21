package com.example.myapplication1.ui.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication1.data.repository.FakeCommunicationRepository
import com.example.myapplication1.data.transport.SipEventListener
import com.example.myapplication1.data.transport.SipManager
import com.example.myapplication1.domain.model.AuthorRole
import com.example.myapplication1.domain.model.ChatMessage
import com.example.myapplication1.domain.model.MessageDeliveryStatus
import com.example.myapplication1.domain.model.MessageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AppViewModel : ViewModel() {

    private val repository = FakeCommunicationRepository()

    private val _uiState = MutableStateFlow(AppUiState(
        conversations = repository.loadConversations(),
        messages = repository.loadMessages()
    ))
    val uiState: StateFlow<AppUiState> = _uiState

    init {
        // 设置 SIP 监听器，确保与底层通讯同步
        SipManager.listener = object : SipEventListener {
            override fun onCallStateChanged(state: String) {
                Log.d("AppViewModel", "呼叫状态变更: $state")
                _uiState.update { it.copy(transportStatus = "通话: $state") }
            }

            override fun onRegistrationStatus(success: Boolean) {
                val status = if (success) "注册成功" else "注册失败"
                _uiState.update { it.copy(transportStatus = "SIP: $status") }
            }

            override fun onMessageReceived(from: String, body: String) {
                handleIncomingSipMessage(from, body)
            }
        }
    }

    fun enterWorkspace() {
        _uiState.update { it.copy(isAuthenticated = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 10.0.2.15 为模拟器 IP, 10.0.2.2 为宿主机 IP
                SipManager.initSipStack("10.0.2.15")
                SipManager.register("102", "10.0.2.2")
            } catch (e: Exception) {
                Log.e("AppViewModel", "SIP 初始化失败: ${e.message}")
            }
        }
    }

    fun selectConversation(vararg args: Any?) {
        val id = args.firstOrNull() as? String
        if (id != null) {
            _uiState.update { it.copy(selectedConversationId = id) }
        }
    }

    // 实现真正的文字消息发送
    fun sendTextMessage(vararg args: Any?) {
        val draft = uiState.value.messageDraft
        val currentConversationId = uiState.value.selectedConversationId ?: "103"

        if (draft.isNotBlank()) {
            val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            
            // 构造符合项目定义的 ChatMessage
            val newMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                author = "me",
                authorRole = AuthorRole.SELF,
                kind = MessageKind.TEXT,
                body = draft,
                timestamp = now,
                deliveryStatus = MessageDeliveryStatus.SENT
            )

            // 更新 UI
            _uiState.update { state ->
                val conversationMessages = state.messages[currentConversationId].orEmpty().toMutableList()
                conversationMessages.add(newMessage)
                state.copy(
                    messages = state.messages + (currentConversationId to conversationMessages),
                    messageDraft = "" // 发送后清空草稿
                )
            }

            // 调用底层的 SIP 发送
            viewModelScope.launch(Dispatchers.IO) {
                SipManager.sendMessage(to = currentConversationId, server = "10.0.2.2", body = draft)
            }
        }
    }

    // 媒体测试按钮：处理呼叫与挂断切换
    fun sendMediaPlaceholder(vararg args: Any?) {
        val currentStatus = uiState.value.transportStatus
        viewModelScope.launch(Dispatchers.IO) {
            if (currentStatus.contains("CONNECTED") || currentStatus.contains("RINGING") || currentStatus.contains("CALLING")) {
                SipManager.hangup()
            } else {
                // 默认呼叫 103 (网页端)
                SipManager.makeCall(caller = "102", callee = "103", server = "10.0.2.2")
            }
        }
    }

    private fun handleIncomingSipMessage(from: String, body: String) {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val senderUser = from.substringAfter("sip:").substringBefore("@")
        val conversationId = senderUser.ifEmpty { "103" }

        val incomingMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            author = senderUser,
            authorRole = AuthorRole.REMOTE,
            kind = MessageKind.TEXT,
            body = body,
            timestamp = now,
            deliveryStatus = MessageDeliveryStatus.DELIVERED
        )

        _uiState.update { state ->
            val conversationMessages = state.messages[conversationId].orEmpty().toMutableList()
            conversationMessages.add(incomingMsg)
            state.copy(messages = state.messages + (conversationId to conversationMessages))
        }
    }

    fun updateDraft(vararg args: Any?) {
        val newDraft = args.firstOrNull() as? String ?: ""
        _uiState.update { it.copy(messageDraft = newDraft) }
    }
}
