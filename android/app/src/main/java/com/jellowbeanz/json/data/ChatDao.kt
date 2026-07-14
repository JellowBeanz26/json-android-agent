package com.jellowbeanz.json.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun conversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC, id ASC")
    fun messages(id: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC, id ASC")
    suspend fun messagesOnce(id: Long): List<Message>

    @Insert
    suspend fun insertConversation(c: Conversation): Long

    @Insert
    suspend fun insertMessage(m: Message): Long

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
