package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GitlabSubjectKeyDeriverTest extends BaseUnitTest {

    private final GitlabSubjectKeyDeriver deriver = new GitlabSubjectKeyDeriver();
    private final GitlabSubjectParser parser = new GitlabSubjectParser();
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
        assertThat(deriver.deriveDedupKey(body, oldKeyHook)).isNotEqualTo(deriver.deriveDedupKey(body, newKeyHook));
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
    void shouldNotDeriveTheConnectionTokenFromAPayloadPath() throws Exception {
        JsonNode project = json("{\"object_kind\":\"push\",\"project\":{\"path_with_namespace\":\"?connection/7\"}}");
        JsonNode lifecycle = json("{\"event_name\":\"project_create\",\"path_with_namespace\":\"?connection/7\"}");

        assertThat(deriver.deriveSubject(project, Map.of())).isEqualTo("gitlab.?.7.push");
        assertThat(deriver.deriveSubject(lifecycle, Map.of())).isEqualTo("gitlab.?.?.project");
    }

    @Test
    void buildsMergeRequestSubjectFromProjectPath() throws Exception {
        JsonNode payload =
                json("{\"object_kind\":\"merge_request\",\"project\":{\"path_with_namespace\":\"group/web\"}}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.group.web.merge_request");
    }

    @Test
    void joinsNestedGroupsWithTilde() throws Exception {
        JsonNode payload =
                json("{\"object_kind\":\"push\",\"project\":{\"path_with_namespace\":\"group/sub/project\"}}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.group~sub.project.push");
    }

    /**
     * Regression guard for the producer/consumer case-folding asymmetry: GitLab paths are
     * case-sensitive and may contain uppercase. The consumer filter
     * (ConsumerSubjectMath#buildSubjectPrefix) is case-preserving, so the deriver must be too —
     * lowercasing the path here silently drops every event for a mixed-case GitLab project.
     */
    @Test
    void preservesPathCase() throws Exception {
        JsonNode payload = json(
                "{\"object_kind\":\"merge_request\",\"project\":{\"path_with_namespace\":\"MyGroup/My-Project\"}}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.MyGroup.My-Project.merge_request");
    }

    @Test
    void sanitizesDotsInPathSegments() throws Exception {
        JsonNode payload = json("{\"object_kind\":\"push\",\"project\":{\"path_with_namespace\":\"group/my.repo\"}}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.group.my~repo.push");
    }

    @Test
    void lowercasesEventTokenOnly() throws Exception {
        JsonNode payload = json("{\"object_kind\":\"Push\",\"project\":{\"path_with_namespace\":\"Group/Repo\"}}");
        // Event is lowercased (Locale.ROOT) but the path keeps its case.
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.Group.Repo.push");
    }

    @Test
    void fallsBackToEventNameWhenObjectKindMissing() throws Exception {
        JsonNode payload = json("{\"event_name\":\"push\",\"project\":{\"path_with_namespace\":\"group/web\"}}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.group.web.push");
    }

    @Test
    void fallsBackToTopLevelPathWhenProjectMissing() throws Exception {
        JsonNode payload = json("{\"object_kind\":\"issue\",\"path_with_namespace\":\"group/web\"}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.group.web.issue");
    }

    @Test
    void singleSegmentPathLeavesProjectAsPlaceholder() throws Exception {
        JsonNode payload = json("{\"object_kind\":\"push\",\"project\":{\"path_with_namespace\":\"group\"}}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.group.?.push");
    }

    @Test
    void placeholdersWhenNoPathOrEvent() throws Exception {
        assertThat(deriver.deriveSubject(json("{}"), Map.of())).isEqualTo("gitlab.?.?.unknown");
    }

    // --- Group-tier live events (project / subgroup / member) ---
    // These events register their handlers under the stable "project"/"subgroup"/"member" keys. Emitting
    // the raw event_name (e.g. "project_create") with a "?" namespace would produce a key no handler owns
    // AND a subject no consumer filter matches — a silent drop. The deriver must normalize the key and
    // carry the root group token so the workspace organizationFilter (gitlab.<accountLogin>.?.>) matches.

    @Test
    void projectEventFixtureNormalizesToProjectKeyAndOrgScope() throws Exception {
        String subject = deriver.deriveSubject(fixture("project.create.json"), Map.of());
        // Root group of "hephaestustest/test-webhook-project" is the workspace accountLogin; project slot
        // is the org-scope placeholder so create/rename/transfer/delete all route regardless of path change.
        assertThat(subject).isEqualTo("gitlab.hephaestustest.?.project");
        assertThat(resolvedEventKey(subject)).isEqualTo(GitLabEventType.PROJECT.getValue());
    }

    @Test
    void subgroupEventFixtureNormalizesToSubgroupKeyAndOrgScope() throws Exception {
        String subject = deriver.deriveSubject(fixture("subgroup.create.json"), Map.of());
        assertThat(subject).isEqualTo("gitlab.hephaestustest.?.subgroup");
        assertThat(resolvedEventKey(subject)).isEqualTo(GitLabEventType.SUBGROUP.getValue());
    }

    @Test
    void memberEventFixtureNormalizesToMemberKey() throws Exception {
        // Member payloads carry only the leaf group_path ("test-subgroup" here), so a subgroup-scoped
        // membership change can't resolve the workspace root — a documented limitation healed by the
        // periodic member sync. Key normalization still holds unconditionally.
        String subject = deriver.deriveSubject(fixture("member.add.json"), Map.of());
        assertThat(subject).isEqualTo("gitlab.test-subgroup.?.member");
        assertThat(resolvedEventKey(subject)).isEqualTo(GitLabEventType.MEMBER.getValue());
    }

    @Test
    void rootGroupMemberEventRoutesToOrgScope() throws Exception {
        // Adding a member to the ROOT group carries group_path == accountLogin, so it routes cleanly.
        JsonNode payload = json(
                "{\"event_name\":\"user_add_to_group\",\"group_path\":\"hephaestustest\",\"group_id\":1,\"user_id\":2}");
        assertThat(deriver.deriveSubject(payload, Map.of())).isEqualTo("gitlab.hephaestustest.?.member");
    }

    @Test
    void normalizesEveryGroupTierEventVerb() throws Exception {
        assertThat(resolvedEventKey(deriver.deriveSubject(json("{\"event_name\":\"project_rename\"}"), Map.of())))
                .isEqualTo("project");
        assertThat(resolvedEventKey(deriver.deriveSubject(json("{\"event_name\":\"project_transfer\"}"), Map.of())))
                .isEqualTo("project");
        assertThat(resolvedEventKey(deriver.deriveSubject(json("{\"event_name\":\"subgroup_destroy\"}"), Map.of())))
                .isEqualTo("subgroup");
        assertThat(resolvedEventKey(
                        deriver.deriveSubject(json("{\"event_name\":\"user_remove_from_group\"}"), Map.of())))
                .isEqualTo("member");
        assertThat(resolvedEventKey(
                        deriver.deriveSubject(json("{\"event_name\":\"user_update_for_group\"}"), Map.of())))
                .isEqualTo("member");
    }

    @Test
    void doesNotFoldProjectMemberVerbsOntoGroupMemberKey() throws Exception {
        // Project-member events (user_*_to_team) are a different handler concern; they must NOT normalize
        // to the group "member" key.
        String subject = deriver.deriveSubject(
                json("{\"event_name\":\"user_add_to_team\",\"project\":{\"path_with_namespace\":\"g/p\"}}"), Map.of());
        assertThat(subject).isEqualTo("gitlab.g.p.user_add_to_team");
    }

    @Test
    void dedupKeyPrefersIdempotencyHeader() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String key = deriver.deriveDedupKey(body, Map.of("Idempotency-Key", "abc-123"));
        assertThat(key).isEqualTo("gitlab-abc-123");
    }

    @Test
    void dedupKeyUsesEventUuidWhenNoIdempotencyKey() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String key =
                deriver.deriveDedupKey(body, Map.of("X-Gitlab-Event-UUID", "11111111-2222-3333-4444-555555555555"));
        assertThat(key).isEqualTo("gitlab-11111111-2222-3333-4444-555555555555");
    }

    @Test
    void dedupKeyFallsBackToTruncatedSha256() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String key = deriver.deriveDedupKey(body, Map.of("X-Gitlab-Event", "Push Hook"));

        assertThat(key).startsWith("gitlab-");
        assertThat(key.substring("gitlab-".length())).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void dedupKeyIsDeterministicWhenFallingBack() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("X-Gitlab-Event", "Push Hook");
        assertThat(deriver.deriveDedupKey(body, headers)).isEqualTo(deriver.deriveDedupKey(body, headers));
    }

    @Test
    void deriverIdentifiesAsGitlabKind() {
        assertThat(deriver.kind()).isEqualTo(IntegrationKind.GITLAB);
    }
}
