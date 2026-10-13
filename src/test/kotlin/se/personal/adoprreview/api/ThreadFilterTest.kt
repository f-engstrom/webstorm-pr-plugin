package se.personal.adoprreview.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.ListResponse
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.api.dto.reviewThreads

class ThreadFilterTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Shapes follow real `GET .../threads` responses; content is made up.
    private val response = """
        {"count": 6, "value": [
          {"id": 1, "status": "active", "isDeleted": false,
           "threadContext": {"filePath": "/src/a.ts", "rightFileStart": {"line": 3, "offset": 1}, "rightFileEnd": {"line": 4, "offset": 10}},
           "pullRequestThreadContext": {"iterationContext": {"firstComparingIteration": 1, "secondComparingIteration": 2}, "changeTrackingId": 7},
           "properties": {"Microsoft.TeamFoundation.Discussion.SupportsMarkdown": {"${'$'}type": "System.Int32", "${'$'}value": 1}},
           "comments": [{"id": 1, "parentCommentId": 0, "author": {"id": "u1", "displayName": "A"}, "content": "Rename this", "commentType": "text"}]},
          {"id": 2, "isDeleted": false,
           "properties": {"CodeReviewThreadType": {"${'$'}type": "System.String", "${'$'}value": "RefUpdate"}},
           "comments": [{"id": 1, "author": {"id": "u1"}, "content": "pushed 1 commit", "commentType": "system"}]},
          {"id": 3, "isDeleted": false,
           "comments": [{"id": 1, "author": {"id": "u1"}, "content": "voted 10", "commentType": "system"}]},
          {"id": 4, "status": "fixed", "isDeleted": false,
           "comments": [{"id": 1, "author": {"id": "u2"}, "content": "General remark", "commentType": "text"}]},
          {"id": 5, "status": "active", "isDeleted": true,
           "threadContext": {"filePath": "/src/a.ts", "leftFileStart": {"line": 1, "offset": 1}},
           "comments": [{"id": 1, "author": {"id": "u1"}, "content": "gone", "commentType": "text"}]},
          {"id": 6, "status": "active", "isDeleted": false,
           "threadContext": {"filePath": "/src/b.ts", "leftFileStart": {"line": 1, "offset": 1}},
           "comments": [{"id": 1, "author": {"id": "u1"}, "content": "deleted", "commentType": "text", "isDeleted": true}]}
        ]}
    """.trimIndent()

    private val threads = json.decodeFromString<ListResponse<CommentThread>>(response).value

    @Test
    fun `system, deleted and empty threads are removed`() {
        assertEquals(listOf(1, 4), threads.reviewThreads().map { it.id })
    }

    @Test
    fun `system threads are detected by property or by system-only comments`() {
        assertEquals(listOf(2, 3), threads.filter { it.isSystem }.map { it.id })
    }

    @Test
    fun `thread context and status are parsed`() {
        val thread = threads.first()
        assertEquals("/src/a.ts", thread.filePath)
        assertEquals(4, thread.threadContext?.rightFileEnd?.line)
        assertEquals(7, thread.pullRequestThreadContext?.changeTrackingId)
        assertEquals(ThreadStatus.ACTIVE, thread.threadStatus)
        assertTrue(threads.first { it.id == 4 }.threadStatus.isResolved)
    }
}
