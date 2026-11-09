package se.personal.adoprreview.conversation

import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.NewThread
import se.personal.adoprreview.api.dto.ThreadStatus

/** [ThreadSource] over [AdoClient]. */
class AdoThreadSource : ThreadSource {
    private val client get() = AdoClient.getInstance()

    override suspend fun threads(pr: PrRef): List<CommentThread> = client.threads(pr.remote, pr.id)
    override suspend fun create(pr: PrRef, thread: NewThread) = client.createThread(pr.remote, pr.id, thread)
    override suspend fun reply(pr: PrRef, threadId: Int, parentCommentId: Int, content: String) =
        client.reply(pr.remote, pr.id, threadId, parentCommentId, content)
    override suspend fun setLiked(pr: PrRef, threadId: Int, commentId: Int, liked: Boolean) =
        client.setLiked(pr.remote, pr.id, threadId, commentId, liked)
    override suspend fun edit(pr: PrRef, threadId: Int, commentId: Int, content: String) =
        client.editComment(pr.remote, pr.id, threadId, commentId, content)
    override suspend fun delete(pr: PrRef, threadId: Int, commentId: Int) = client.deleteComment(pr.remote, pr.id, threadId, commentId)
    override suspend fun setStatus(pr: PrRef, threadId: Int, status: ThreadStatus) =
        client.setThreadStatus(pr.remote, pr.id, threadId, status)
    override suspend fun displayNames(org: String, ids: Set<String>): Map<String, String> =
        client.identities(org, ids).mapNotNull { identity -> identity.displayName?.let { identity.id to it } }.toMap()
}
