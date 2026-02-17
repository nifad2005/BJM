package com.example.bjm

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM friends")
    fun getAllFriends(): Flow<List<Friend>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: Friend)

    @Update
    suspend fun updateFriend(friend: Friend)

    @Query("SELECT * FROM messages WHERE (senderId = :friendId AND receiverId = :myId) OR (senderId = :myId AND receiverId = :friendId) ORDER BY timestamp ASC")
    fun getMessagesWithFriend(friendId: String, myId: String): Flow<List<Message>>

    @Insert
    suspend fun insertMessage(message: Message)
}

@Database(entities = [Friend::class, Message::class], version = 1)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
