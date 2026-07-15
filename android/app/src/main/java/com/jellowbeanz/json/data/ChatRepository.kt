package com.jellowbeanz.json.data

/** Thin layer over the DAO so the ViewModel never touches Room directly. */
class ChatRepository(private val dao: ChatDao) {

    fun conversations() = dao.conversations()

    fun messages(id: Long) = dao.messages(id)

    /**
     * Messages for the model: action/note rows (from an agent run) are treated as assistant content, and
     * consecutive same-role turns are merged — so the model sees a clean alternating transcript AND can
     * discuss what the agent did, even when a run was stopped partway.
     */
    suspend fun history(id: Long): List<Message> = collapseForLlm(dao.messagesOnce(id))

    private fun collapseForLlm(messages: List<Message>): List<Message> {
        val out = mutableListOf<Message>()
        for (m in messages) {
            val role = if (m.role == "user") "user" else "assistant"
            val last = out.lastOrNull()
            if (last != null && last.role == role) {
                out[out.size - 1] = last.copy(text = last.text + "\n" + m.text)
            } else {
                out.add(m.copy(role = role))
            }
        }
        return out
    }

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
