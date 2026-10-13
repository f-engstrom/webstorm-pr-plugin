package se.personal.adoprreview.api

import org.junit.Assert.assertEquals
import org.junit.Test
import se.personal.adoprreview.api.dto.ChangeKind
import se.personal.adoprreview.api.dto.ChangesResponse

class ChangesTest {

    @Test
    fun `deleted files have a null item path and use originalPath`() {
        // Shape of a real `.../iterations/{id}/changes` entry for a deleted file.
        val response = """
            {"changeEntries": [
              {"changeTrackingId": 80, "changeId": 80, "changeType": "delete", "originalPath": "/src/old/Gone.ts",
               "item": {"originalObjectId": "abc123", "path": null}},
              {"changeTrackingId": 81, "changeId": 81, "changeType": "edit",
               "item": {"objectId": "def456", "originalObjectId": "aaa111", "path": "/src/Kept.ts"}}
            ]}
        """.trimIndent()

        val (deleted, edited) = AdoClient.json.decodeFromString<ChangesResponse>(response).changeEntries

        assertEquals("/src/old/Gone.ts", deleted.path)
        assertEquals("/src/old/Gone.ts", deleted.basePath)
        assertEquals(ChangeKind.DELETE, deleted.kind)
        assertEquals("/src/Kept.ts", edited.path)
    }
}
