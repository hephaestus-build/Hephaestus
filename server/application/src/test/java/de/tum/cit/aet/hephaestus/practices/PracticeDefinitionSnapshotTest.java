package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PracticeDefinitionSnapshotTest extends BaseUnitTest {
    @Test
    void shouldKeepOnlyVersionIdentityAndDigestWhenAnAuditSnapshotIsWritten() {
        var version = TestEntities.practiceVersion(
                17,
                1,
                JsonMapper.builder().build().valueToTree(DefinitionTestFixtures.definition()),
                "v6:" + "a".repeat(64));
        var practice = new Practice();
        practice.setSlug("private-script");
        practice.setKind(new ItemKind("scm.pull_request"));
        practice.setCurrentVersion(version);
        var snapshot =
                PracticeDefinitionSnapshot.of(practice, JsonMapper.builder().build());
        var json = JsonMapper.builder().build().valueToTree(snapshot);
        assertThat(snapshot.versionId()).isEqualTo(17L);
        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder(
                        "slug",
                        "state",
                        "editedIn",
                        "kind",
                        "versionId",
                        "reviewDigest",
                        "groupSlug",
                        "position",
                        "feedbackMode",
                        "catalogSlug",
                        "catalogBaseDigest");
        assertThat(json.path("slug").asString()).isEqualTo("private-script");
        assertThat(json.path("reviewDigest").asString()).isEqualTo(snapshot.reviewDigest());
    }
}
