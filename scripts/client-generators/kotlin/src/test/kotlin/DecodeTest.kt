// Not under build/hephaestus/api/: the repository ignores every `build/` directory.
package build.hephaestus.api

import build.hephaestus.api.infrastructure.Serializer
import build.hephaestus.api.models.ProblemDetail
import build.hephaestus.api.models.WorkspaceListItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Decodes a fixture from scripts/client-generators/fixtures, as the generated client decodes a response body. */
inline fun <reified T> decodeFixture(name: String): T {
    val stream = checkNotNull(DecodeTest::class.java.getResourceAsStream("/fixtures/$name.json")) { "No fixture $name" }
    return Serializer.kotlinxSerializationJson.decodeFromString(stream.bufferedReader().readText())
}

class DecodeTest {
    @Test
    fun ignoresAnUnknownResponseProperty() {
        val workspace = decodeFixture<WorkspaceListItem>("workspace-list-item")
        assertEquals("octo-org", workspace.workspaceSlug)
        assertEquals("ACTIVE", workspace.status)
    }

    @Test
    fun keepsAValueOfAnOpenEnumThatItDoesNotKnow() {
        val workspace = decodeFixture<WorkspaceListItem>("workspace-list-item-unknown-status")
        assertEquals("PAUSED_IN_A_LATER_RELEASE", workspace.status)
    }

    @Test
    fun keepsAProblemTypeThatItDoesNotKnow() {
        val problem = decodeFixture<ProblemDetail>("problem-unknown-type")
        assertEquals("https://example.com/problems/added-in-a-later-release", problem.type)
        assertEquals(409, problem.status)
        assertEquals("Added in a later release", problem.title)
    }
}
