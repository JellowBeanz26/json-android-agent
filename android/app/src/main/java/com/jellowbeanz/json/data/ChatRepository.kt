package com.jellowbeanz.json.data

/** Thin layer over the DAO so the ViewModel never touches Room directly. */
class ChatRepository(private val dao: ChatDao) {

    fun conversations() = dao.conversations()

    fun messages(id: Long) = dao.messages(id)

    suspend fun history(id: Long): List<Message> = dao.messagesOnce(id)

    suspend fun createConversation(title: String, now: Long): Long =
        dao.insertConversation(Conversation(title = title, createdAt = now, updatedAt = now))

    suspend fun addMessage(conversationId: Long, role: String, text: String, now: Long, reasoning: String = "") {
        dao.insertMessage(
            Message(conversationId = conversationId, role = role, text = text, createdAt = now, reasoning = reasoning),
        )
        dao.touch(conversationId, now)
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title)

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun delete(id: Long) = dao.deleteConversation(id)

    suspend fun deleteAll() = dao.deleteAllConversations()
}
