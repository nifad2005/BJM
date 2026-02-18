package com.example.bjm

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageStatus { PENDING, SENT, DELIVERED, SEEN }

@Entity(tableName = "friends")
data class Friend(
    @PrimaryKey val id: String,
    val name: String,
    val isOnline: Boolean = false,
    val lastSeen: Long = 0,
    val isTyping: Boolean = false,
    val profilePic: String? = null,
    val lastMessageTimestamp: Long = 0
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["messageUuid"], unique = true)]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSentByMe: Boolean = true,
    val status: MessageStatus = MessageStatus.SENT,
    val messageUuid: String = ""
)
