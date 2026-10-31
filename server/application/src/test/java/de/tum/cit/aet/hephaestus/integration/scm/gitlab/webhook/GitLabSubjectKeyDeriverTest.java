package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GitLabSubjectKeyDeriverTest extends BaseUnitTest {

    private final GitLabSubjectKeyDeriver deriver = new GitLabSubjectKeyDeriver();
    private final GitLabSubjectParser parser = new GitLabSubjectParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private JsonNode fixture(String name) throws Exception {
        return objectMapper.readTree(Files.readAllBytes(Path.of("src/test/resources/gitlab", name)));
    }

    /** The event token a consumer would resolve from this subject — the handler-registry lookup key. */
    private String resolvedEventKey(String subject) {
        EventTypeKey key = parser.parse(subject);
        return key.eventType();
    }

    @Test
    void shouldScopeConnectionSubjectToTheConnectionWhateverThePayloadPath() throws Exception {
        JsonNode member = json("{\"event_name\":\"user_add_to_group\",\"group_path\":\"leaf\"}");
        JsonNode issue = json("{\"object_kind\":\"issue\",\"project\":{\"path_with_namespace\":\"other/group/p\"}}");

        assertThat(deriver.deriveConnectionSubject(7L, member)).isEqualTo("gitlab.?connection.7.member");
        assertThat(deriver.deriveConnectionSubject(7L, issue)).isEqualTo("gitlab.?connection.7.issue");
        assertThat(resolvedEventKey(deriver.deriveConnectionSubject(7L, issue))).isEqualTo("issue");
    }

    @Test
    void shouldDedupOneEventThatTwoHooksOfAConnectionDeliverButKeepEachConnectionsCopy() {
        byte[] body = "{\"object_kind\":\"issue\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> oldKeyHook = Map.of(
                "X-Gitlab-Event-UUID", "event-1", "Idempotency-Key", "delivery-a", "X-Gitlab-Event", "Issue Hook");
        Map<String, String> newKeyHook = Map.of(
                "X-Gitlab-Event-UUID", "event-1", "Idempotency-Key", "delivery-b", "X-Gitlab-Event", "Issue Hook");

        String first = deriver.deriveConnectionDedupKey(7L, body, oldKeyHook);

        assertThat(deriver.deriveConnectionDedupKey(7L, body, newKeyHook)).isEqualTo(first);
        assertThat(deriver.deriveConnectionDedupKey(8L, body, newKeyHook)).isNotEqualTo(first);
    }

    @Test
    void shouldKeepRecursiveEventsThatShareAnEventUuidApart() {
        Map<String, String> headers = Map.of("X-Gitlab-Event-UUID", "event-1", "X-Gitlab-Event", "Note Hook");

        assertThat(deriver.deriveConnectionDedupKey(
                        7L, "{\"object_kind\":\"note\",\"id\":1}".getBytes(StandardCharsets.UTF_8), headers))
                .isNotEqualTo(deriver.deriveConnectionDedupKey(
                        7L, "{\"object_kind\":\"note\",\"id\":2}".getBytes(StandardCharsets.UTF_8), headers));
    }

    @Test
    void lowercasesAndSanitizesTheEventToken() throws Exception {
        assertThat(deriver.deriveConnectionSubject(7L, json("{\"object_kind\":\"Push\"}")))
                .isEqualTo("gitlab.?connection.7.push");
        assertThat(deriver.deriveConnectionSubject(7L, json("{\"object_kind\":\"merge.request\"}")))
                .isEqualTo("gitlab.?connection.7.merge~request");
    }

    @Test
    void fallsBackToEventNameThenToUnknown() throws Exception {
        assertThat(deriver.deriveConnectionSubject(7L, json("{\"event_name\":\"push\"}")))
                .isEqualTo("gitlab.?connection.7.push");
        assertThat(deriver.deriveConnectionSubject(7L, json("{}"))).isEqualTo("gitlab.?connection.7.unknown");
    }

    // These events register their handlers under the stable "project"/"subgroup"/"member" keys. Emitting
    // the raw event_name (e.g. "project_create") would produce a key no handler owns.

    @Test
    void normalizesGroupTierFixturesOntoTheirHandlerKeys() throws Exception {
        assertThat(resolvedEventKey(deriver.deriveConnectionSubject(7L, fixture("project.create.json"))))
                .isEqualTo(GitLabEventType.PROJECT.getValue());
        assertThat(resolvedEventKey(deriver.deriveConnectionSubject(7L, fixture("subgroup.create.json"))))
                .isEqualTo(GitLabEventType.SUBGROUP.getValue());
        assertThat(resolvedEventKey(deriver.deriveConnectionSubject(7L, fixture("member.add.json"))))
                .isEqualTo(GitLabEventType.MEMBER.getValue());
    }

    @Test
    void normalizesEveryGroupTierEventVerb() throws Exception {
        for (String[] verb : new String[][] {
            {"project_rename", "project"},
            {"project_transfer", "project"},
            {"subgroup_destroy", "subgroup"},
            {"user_remove_from_group", "member"},
            {"user_update_for_group", "member"},
        }) {
            JsonNode payload = json("{\"event_name\":\"" + verb[0] + "\"}");
            assertThat(resolvedEventKey(deriver.deriveConnectionSubject(7L, payload)))
                    .isEqualTo(verb[1]);
        }
    }

    @Test
    void doesNotFoldProjectMemberVerbsOntoGroupMemberKey() throws Exception {
        // Project-member events (user_*_to_team) are a different handler concern; they must NOT normalize
        // to the group "member" key.
        assertThat(deriver.deriveConnectionSubject(7L, json("{\"event_name\":\"user_add_to_team\"}")))
                .isEqualTo("gitlab.?connection.7.user_add_to_team");
    }
}
