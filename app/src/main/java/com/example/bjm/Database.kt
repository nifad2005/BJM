package com.example.bjm

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM friends ORDER BY lastMessageTimestamp DESC")
    fun getAllFriends(): Flow<List<Friend>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFriend(friend: Friend)

    @Update
    suspend fun updateFriend(friend: Friend)

    @Query("UPDATE friends SET isOnline = :status, lastSeen = :lastSeen WHERE id = :friendId")
    suspend fun updateFriendPresence(friendId: String, status: Boolean, lastSeen: Long)

    @Query("UPDATE friends SET isTyping = :isTyping WHERE id = :friendId")
    suspend fun updateFriendTyping(friendId: String, isTyping: Boolean)

    @Query("UPDATE friends SET name = :name WHERE id = :friendId")
    suspend fun updateFriendName(friendId: String, name: String)

    @Query("UPDATE friends SET profilePic = :profilePic WHERE id = :friendId")
    suspend fun updateFriendPic(friendId: String, profilePic: String?)

    @Query("UPDATE friends SET lastMessageTimestamp = :timestamp WHERE id = :friendId")
    suspend fun updateFriendLastMessageTime(friendId: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE (senderId = :friendId AND receiverId = :myId) OR (senderId = :myId AND receiverId = :friendId) ORDER BY timestamp ASC")
    fun getMessagesWithFriend(friendId: String, myId: String): Flow<List<Message>>

    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: Int, status: MessageStatus)

    @Query("UPDATE messages SET status = :status WHERE receiverId = :friendId AND senderId = :myId AND status != 'SEEN'")
    suspend fun updateSentMessagesStatus(friendId: String, myId: String, status: MessageStatus)

    @Query("SELECT * FROM messages WHERE (status = 'PENDING' OR status = 'SENT') AND senderId = :myId")
    suspend fun getUndeliveredMessages(myId: String): List<Message>
    
    @Query("UPDATE messages SET status = 'SEEN' WHERE senderId = :friendId AND receiverId = :myId AND status != 'SEEN'")
    suspend fun markMessagesAsSeen(friendId: String, myId: String)
}

@Database(entities = [Friend::class, Message::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chat_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
