package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PracticeIdentitiesTest extends BaseUnitTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String OLD = "v6:" + "a".repeat(64);
    private static final String REWORDED = "v6:" + "b".repeat(64);
    private static final String CHANGED = "v6:" + "c".repeat(64);

    @Test
    void shouldGiveEveryBundledPracticeADomainQualifiedPortableKey() throws IOException {
        var identities = new PracticeIdentities();
        JsonNode catalog;
        try (InputStream input = new ClassPathResource(PracticeIdentities.CATALOG).getInputStream()) {
            catalog = MAPPER.readTree(input);
        }
        for (JsonNode group : catalog.path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                String slug = practice.path("slug").asString();
                assertThat(identities.keyOf(slug, practice.path("kind").asString()))
                        .as(slug)
                        .hasValueSatisfying(key ->
                                assertThat(PracticeIdentities.portable(key)).isTrue());
            }
        }
    }

    @Test
    void shouldStartANewClassWhenAnEditChangesTheJudgement() {
        var identities = PracticeIdentities.of(catalog("""
                {"slug": "states-how-to-verify", "kind": "scm.pull_request", "identity": {"domain": "software-engineering", "key": "verify",
                  "classes": [["%s", "%s"]]}}
                """.formatted(OLD, REWORDED)));
        String key =
                identities.keyOf("states-how-to-verify", "scm.pull_request").orElseThrow();
        assertThat(key).isEqualTo("software-engineering/verify");
        // The catalog declares that the reworded digest judges the same thing, so it keeps the class.
        assertThat(identities.comparabilityClass(key, REWORDED)).isEqualTo(OLD);
        assertThat(identities.comparabilityClass(key, OLD)).isEqualTo(OLD);
        // An edit that changes the judgement has a digest in no class, so it starts its own.
        assertThat(identities.comparabilityClass(key, CHANGED))
                .isEqualTo(CHANGED)
                .isNotEqualTo(OLD);
    }

    @Test
    void shouldResolveTheClassesOfAPracticeByItsFormerKey() {
        var identities = PracticeIdentities.of(catalog("""
                {"slug": "verify", "identity": {"domain": "software-engineering", "key": "verify",
                  "classes": [["%s", "%s"]], "formerly": ["software-engineering/test-plan"]}}
                """.formatted(OLD, REWORDED)));
        assertThat(identities.comparabilityClass("software-engineering/test-plan", REWORDED))
                .isEqualTo(OLD);
    }

    @Test
    void shouldGiveAPracticeOutsideTheCatalogALocalKeyThatNeverTravels() {
        var identities = PracticeIdentities.of(catalog("""
                {"slug": "verify", "kind": "scm.pull_request",
                  "identity": {"domain": "software-engineering", "key": "verify"}}
                """));
        String local = identities.keyOf(null, "scm.pull_request", 7, 11);
        assertThat(local).isEqualTo("local/7/11");
        assertThat(PracticeIdentities.portable(local)).isFalse();
        assertThat(identities.keyOf("written-by-an-admin", "scm.pull_request", 7, 11))
                .isEqualTo(local);
        // An instance entry that moved the practice to another item kind judges something else.
        assertThat(identities.keyOf("verify", "scm.issue", 7, 11)).isEqualTo(local);
        assertThat(identities.keyOf("verify", "scm.pull_request", 7, 11)).isEqualTo("software-engineering/verify");
    }

    @Test
    void shouldReserveARetiredKeyAndStillResolveItsClasses() {
        String retired = """
                "retiredIdentities": [{"kind": "scm.pull_request", "identity": {"domain": "software-engineering",
                  "key": "verify", "classes": [["%s", "%s"]]}}]""".formatted(OLD, REWORDED);
        var identities = PracticeIdentities.of(MAPPER.readTree("{" + retired + ", \"groups\": []}"));
        assertThat(identities.comparabilityClass("software-engineering/verify", REWORDED))
                .isEqualTo(OLD);
        // A new adoption at the former slug, from an instance entry that survived, gets a local key.
        assertThat(identities.keyOf("verify", "scm.pull_request", 7, 11)).isEqualTo("local/7/11");
        // A retired identity records the item kind it had.
        assertThatThrownBy(() -> PracticeIdentities.of(MAPPER.readTree("""
                        {"groups": [], "retiredIdentities": [{"identity": {"domain": "software-engineering", "key": "verify"}}]}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired identity 0")
                .hasMessageContaining("needs a kind");
        assertThatThrownBy(() -> PracticeIdentities.of(MAPPER.readTree("""
                        {"groups": [], "retiredIdentities": [{"kind": "Pull Request",
                          "identity": {"domain": "software-engineering", "key": "verify"}}]}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("its kind is no item kind");
        // A practice that the catalog ships cannot take the retired key.
        assertThatThrownBy(() -> PracticeIdentities.of(MAPPER.readTree("{" + retired + """
                        , "groups": [{"practices": [{"slug": "verify", "kind": "scm.pull_request",
                          "identity": {"domain": "software-engineering", "key": "verify"}}]}]}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("software-engineering/verify");
    }

    @Test
    void shouldRefuseTwoEntriesWithOneKeyAndDifferentClasses() {
        assertThatThrownBy(() -> PracticeIdentities.of(catalog("""
                        {"slug": "verify-pull-request", "identity": {"domain": "software-engineering",
                          "key": "verify", "classes": [["%s"]]}},
                        {"slug": "verify-issue", "identity": {"domain": "software-engineering",
                          "key": "verify", "classes": [["%s"]]}}
                        """.formatted(OLD, CHANGED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("software-engineering/verify with different comparability classes");
    }

    @Test
    void shouldRefuseConflictingIdentityMetadata() {
        String verify = """
                {"slug": "verify", "identity": {"domain": "software-engineering", "key": "verify"}}""";
        for (String conflict : new String[] {
            // A former key that a live practice holds.
            verify + """
                    , {"slug": "plan", "identity": {"domain": "software-engineering", "key": "plan",
                      "formerly": ["software-engineering/verify"]}}""",
            // A related key that names no practice.
            """
                    {"slug": "plan", "identity": {"domain": "software-engineering", "key": "plan",
                      "related": ["software-engineering/missing"]}}""",
            // One digest in two classes.
            """
                    {"slug": "plan", "identity": {"domain": "software-engineering", "key": "plan",
                      "classes": [["%s"], ["%s", "%s"]]}}""".formatted(OLD, CHANGED, OLD),
            // A digest whose function version has more than three digits.
            """
                    {"slug": "plan", "identity": {"domain": "software-engineering", "key": "plan",
                      "classes": [["v1234:%s"]]}}""".formatted("a".repeat(64)),
            // The reserved local domain.
            """
                    {"slug": "plan", "identity": {"domain": "local", "key": "plan"}}""",
            // No identity.
            """
                    {"slug": "plan"}"""
        }) {
            assertThatThrownBy(() -> PracticeIdentities.of(catalog(conflict)))
                    .as(conflict)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static JsonNode catalog(String practices) {
        return MAPPER.readTree("{\"groups\": [{\"practices\": [" + practices + "]}]}");
    }
}
