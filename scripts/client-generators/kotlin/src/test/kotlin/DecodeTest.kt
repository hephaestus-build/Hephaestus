// Not under build/hephaestus/api/: the repository ignores every `build/` directory.
package build.hephaestus.api

import build.hephaestus.api.infrastructure.Serializer
import build.hephaestus.api.models.WorkspaceListItem
import build.hephaestus.api.models.WorkspaceStatus
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
        assertEquals(WorkspaceStatus.ACTIVE, workspace.status)
    }
}
