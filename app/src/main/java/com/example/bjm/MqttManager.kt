package com.example.bjm

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class MqttManager(private val context: Context, private val dao: ChatDao) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val myId: String = getOrCreateClientId()
    private var client: Mqtt5AsyncClient? = null
    private val notificationHelper = NotificationHelper(context)
    private val subscribedFriends = mutableSetOf<String>()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private fun getOrCreateClientId(): String {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("my_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("my_id", id).apply()
        }
        return id
    }

    fun getMyId() = myId

    fun getMyName(): String {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        return prefs.getString("my_name", "User_${myId.take(4)}") ?: "User"
    }

    fun getMyProfilePic(): String? {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        return prefs.getString("my_profile_pic", null)
    }

    fun updateMyProfile(name: String, profilePicBase64: String?) {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("my_name", name).putString("my_profile_pic", profilePicBase64).apply()
        publishProfileUpdate()
    }

    fun connect() {
        if (client != null && _isConnected.value) return

        try {
            client = MqttClient.builder()
                .useMqttVersion5()
                .identifier(myId)
                .serverHost("broker.hivemq.com")
                .serverPort(1883)
                .automaticReconnectWithDefaultConfig()
                .buildAsync()

            client?.connectWith()
                ?.cleanStart(false)
                ?.sessionExpiryInterval(3600 * 24)
                ?.send()
                ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    _isConnected.value = false
                    Log.e("MqttManager", "Connection failed", throwable)
                } else {
                    _isConnected.value = true
                    subscribeToTopics()
                    publishOnlineStatus(true)
                    publishProfileUpdate()
                    resendPendingMessages()
                    Log.d("MqttManager", "Connected successfully")
                }
            }
        } catch (e: Exception) {
            Log.e("MqttManager", "Error building MQTT client", e)
        }
    }

    private fun subscribeToTopics() {
        client?.subscribeWith()
            ?.topicFilter("bjm/chat/$myId")
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes)
                handleIncomingMessage(payload)
            }
            ?.send()

        client?.subscribeWith()
            ?.topicFilter("bjm/typing/$myId")
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes)
                handleTypingSignal(payload)
            }
            ?.send()
        
        client?.subscribeWith()
            ?.topicFilter("bjm/ack/$myId")
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes)
                handleAck(payload)
            }
            ?.send()
        
        scope.launch {
            dao.getAllFriends().collect { friends ->
                friends.forEach { friend ->
                    if (!subscribedFriends.contains(friend.id)) {
                        subscribeToPresence(friend.id)
                        subscribeToProfile(friend.id)
                        subscribedFriends.add(friend.id)
                    }
                }
            }
        }
    }

    private fun subscribeToPresence(friendId: String) {
        client?.subscribeWith()
            ?.topicFilter("bjm/presence/$friendId")
            ?.callback { publish ->
                val status = String(publish.payloadAsBytes) == "online"
                scope.launch {
                    dao.updateFriendPresence(friendId, status, System.currentTimeMillis())
                    if (status) {
                        resendPendingToFriend(friendId)
                    }
                }
            }
            ?.send()
    }

    private fun subscribeToProfile(friendId: String) {
        client?.subscribeWith()
            ?.topicFilter("bjm/profile/$friendId")
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes)
                handleProfileSignal(friendId, payload)
            }
            ?.send()
    }

    private fun handleIncomingMessage(payload: String) {
        val parts = payload.split(":", limit = 3)
        if (parts.size == 3) {
            val senderId = parts[0]
            val messageId = parts[1]
            val content = parts[2]
            scope.launch {
                dao.insertMessage(Message(senderId = senderId, receiverId = myId, content = content, isSentByMe = false))
                dao.insertFriend(Friend(senderId, senderId, true, System.currentTimeMillis(), lastMessageTimestamp = System.currentTimeMillis()))
                dao.updateFriendLastMessageTime(senderId, System.currentTimeMillis())
                sendAck(senderId, messageId, MessageStatus.DELIVERED)
                notificationHelper.showNotification(senderId, content)
            }
        }
    }

    private fun handleAck(payload: String) {
        val parts = payload.split(":")
        if (parts.size == 3) {
            val senderId = parts[0]
            val messageIdStr = parts[1]
            val status = MessageStatus.valueOf(parts[2])
            
            scope.launch {
                if (messageIdStr == "ALL") {
                    dao.updateSentMessagesStatus(senderId, myId, status)
                } else {
                    val messageId = messageIdStr.toIntOrNull()
                    if (messageId != null) {
                        dao.updateMessageStatus(messageId, status)
                    }
                }
            }
        }
    }

    private fun sendAck(friendId: String, messageId: String, status: MessageStatus) {
        val payload = "$myId:$messageId:$status"
        client?.publishWith()
            ?.topic("bjm/ack/$friendId")
            ?.payload(payload.toByteArray())
            ?.send()
    }

    fun sendMessage(friendId: String, content: String) {
        scope.launch {
            val timestamp = System.currentTimeMillis()
            val localId = dao.insertMessage(Message(senderId = myId, receiverId = friendId, content = content, isSentByMe = true, status = MessageStatus.PENDING, timestamp = timestamp)).toInt()
            dao.updateFriendLastMessageTime(friendId, timestamp)
            
            if (_isConnected.value) {
                sendMqttMessage(friendId, localId, content)
            }
        }
    }

    private fun sendMqttMessage(friendId: String, localId: Int, content: String) {
        val payload = "$myId:$localId:$content"
        client?.publishWith()
            ?.topic("bjm/chat/$friendId")
            ?.payload(payload.toByteArray())
            ?.send()?.whenComplete { _, throwable ->
                if (throwable == null) {
                    scope.launch { dao.updateMessageStatus(localId, MessageStatus.SENT) }
                }
            }
    }

    private fun resendPendingMessages() {
        scope.launch {
            val pending = dao.getUndeliveredMessages(myId)
            pending.forEach { msg ->
                sendMqttMessage(msg.receiverId, msg.id, msg.content)
            }
        }
    }

    private fun resendPendingToFriend(friendId: String) {
        scope.launch {
            val pending = dao.getUndeliveredMessages(myId).filter { it.receiverId == friendId }
            pending.forEach { msg ->
                sendMqttMessage(msg.receiverId, msg.id, msg.content)
            }
        }
    }

    fun markAsSeen(friendId: String) {
        scope.launch {
            dao.markMessagesAsSeen(friendId, myId)
            val payload = "$myId:ALL:SEEN"
            client?.publishWith()
                ?.topic("bjm/ack/$friendId")
                ?.payload(payload.toByteArray())
                ?.send()
        }
    }

    private fun handleTypingSignal(payload: String) {
        val parts = payload.split(":")
        if (parts.size == 2) {
            val senderId = parts[0]
            val isTyping = parts[1] == "true"
            scope.launch {
                dao.updateFriendTyping(senderId, isTyping)
                if (isTyping) {
                    delay(5000)
                    dao.updateFriendTyping(senderId, false)
                }
            }
        }
    }

    private fun handleProfileSignal(friendId: String, payload: String) {
        val parts = payload.split("|", limit = 2)
        if (parts.size >= 1) {
            val name = parts[0]
            val profilePic = if (parts.size == 2) parts[1] else null
            scope.launch {
                dao.updateFriendName(friendId, name)
                if (profilePic != null) {
                    dao.updateFriendPic(friendId, profilePic)
                }
            }
        }
    }

    fun sendTypingSignal(friendId: String, isTyping: Boolean) {
        val payload = "$myId:$isTyping"
        client?.publishWith()
            ?.topic("bjm/typing/$friendId")
            ?.payload(payload.toByteArray())
            ?.send()
    }

    private fun publishOnlineStatus(online: Boolean) {
        val status = if (online) "online" else "offline"
        client?.publishWith()
            ?.topic("bjm/presence/$myId")
            ?.payload(status.toByteArray())
            ?.retain(true)
            ?.send()
    }

    private fun publishProfileUpdate() {
        val name = getMyName()
        val pic = getMyProfilePic()
        val payload = if (pic != null) "$name|$pic" else name
        client?.publishWith()
            ?.topic("bjm/profile/$myId")
            ?.payload(payload.toByteArray())
            ?.retain(true)
            ?.send()
    }

    fun disconnect() {
        publishOnlineStatus(false)
        client?.disconnect()
    }
}
