package com.example.bjm

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MqttManager(private val context: Context, private val dao: ChatDao) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val myId: String = getOrCreateClientId()
    private var client: Mqtt5AsyncClient? = null
    private val notificationHelper = NotificationHelper(context)

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

    fun connect() {
        if (client != null && _isConnected.value) return

        client = MqttClient.builder()
            .useMqttVersion5()
            .identifier(myId)
            .serverHost("broker.hivemq.com")
            .serverPort(1883)
            .automaticReconnectWithDefaultConfig()
            .buildAsync()

        client?.connect()?.whenComplete { _, throwable ->
            if (throwable != null) {
                _isConnected.value = false
                Log.e("MqttManager", "Connection failed", throwable)
            } else {
                _isConnected.value = true
                subscribeToMyTopic()
                publishOnlineStatus(true)
                Log.d("MqttManager", "Connected successfully")
            }
        }
    }

    private fun subscribeToMyTopic() {
        client?.subscribeWith()
            ?.topicFilter("bjm/chat/$myId")
            ?.callback { publish ->
                val payload = String(publish.payloadAsBytes)
                handleIncomingMessage(payload)
            }
            ?.send()
        
        // Subscribe to presence topics of friends
        scope.launch {
            dao.getAllFriends().collect { friends ->
                friends.forEach { friend ->
                    subscribeToPresence(friend.id)
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
                    dao.updateFriend(Friend(friendId, friendId, status, System.currentTimeMillis()))
                }
            }
            ?.send()
    }

    private fun handleIncomingMessage(payload: String) {
        // Simple protocol: senderId:content
        val parts = payload.split(":", limit = 2)
        if (parts.size == 2) {
            val senderId = parts[0]
            val content = parts[1]
            scope.launch {
                dao.insertMessage(Message(senderId = senderId, receiverId = myId, content = content, isSent = false))
                // Also ensure friend is in DB
                dao.insertFriend(Friend(senderId, senderId, true, System.currentTimeMillis()))
                
                // Show notification for incoming message
                notificationHelper.showNotification(senderId, content)
            }
        }
    }

    fun sendMessage(friendId: String, content: String) {
        val payload = "$myId:$content"
        client?.publishWith()
            ?.topic("bjm/chat/$friendId")
            ?.payload(payload.toByteArray())
            ?.send()

        scope.launch {
            dao.insertMessage(Message(senderId = myId, receiverId = friendId, content = content, isSent = true))
        }
    }

    private fun publishOnlineStatus(online: Boolean) {
        val status = if (online) "online" else "offline"
        client?.publishWith()
            ?.topic("bjm/presence/$myId")
            ?.payload(status.toByteArray())
            ?.retain(true)
            ?.send()
    }

    fun disconnect() {
        publishOnlineStatus(false)
        client?.disconnect()
    }
}
