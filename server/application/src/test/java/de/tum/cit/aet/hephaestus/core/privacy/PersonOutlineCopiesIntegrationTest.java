package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonOutlineCopiesIntegrationTest extends BaseIntegrationTest {
    private static final String COPIED_WORDS = "Outline collaborator canary";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonDataService service;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private OutlineDocumentRepository documents;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService conversationEntries;

    @Autowired
    private ConversationEntryRepository entries;

    @Test
    void instanceOnlyErasureHidesTeamDocumentsAndRemovesConversationCopies() {
        eraseTeamDocuments(null);
    }

    @Test
    void explicitTeamErasureKeepsTheOtherTeamsDocument() {
        eraseTeamDocuments("team-one");
    }

    private void eraseTeamDocuments(@Nullable String team) {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OUTLINE, "https://outline-instance.example.test"));
        var otherProvider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OUTLINE, "https://other-outline-instance.example.test"));
        String subject = UUID.randomUUID().toString();
        var first = teamDocument(provider, "team-one", subject);
        var second = teamDocument(provider, "team-two", subject);
        second.setCreatedBySubject(subject);
        second.setUpdatedBySubject(subject);
        documents.saveAndFlush(second);
        var control = teamDocument(otherProvider, "team-one", subject);
        var colleague = accounts.saveAndFlush(new Account("Colleague"));
        var link = new Identity();
        link.setAccount(colleague);
        link.setProviderId(Objects.requireNonNull(otherProvider.getId()));
        link.setProviderUserKey(UUID.randomUUID().toString());
        link.setTeamKey("team-one");
        identities.saveAndFlush(link);
        long colleagueId = Objects.requireNonNull(colleague.getId());
        var copiedEntries = copyInConversation(first, colleagueId);
        var controlEntries = copyInConversation(control, colleagueId);
        assertThat(documents.findByWorkspaceIdAndIdForProjection(first.getWorkspaceId(), first.getId()))
                .hasValueSatisfying(
                        document -> assertThat(document.getBodyMarkdown()).isEqualTo(COPIED_WORDS));
        var admin = new Account("Administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        var identity = new PersonIdentity(Objects.requireNonNull(provider.getId()), subject, team);
        var requestId =
                service.preview(adminId, null, List.of(identity)).request().getId();
        var selected = team == null ? List.of(first, second) : List.of(first);

        service.requestErasure(requestId, adminId, true);

        assertSoftly(softly -> {
            for (var document : selected) {
                long workspaceId = document.getWorkspaceId();
                softly.assertThat(documents.findByWorkspaceIdAndIdForProjection(workspaceId, document.getId()))
                        .as("the document is hidden as soon as erasure starts")
                        .isEmpty();
                softly.assertThat(documents.searchByRelevance(workspaceId, "canary", 10))
                        .as("search hides the document as soon as erasure starts")
                        .isEmpty();
            }
            softly.assertThat(service.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.ERASING);
            if (team != null)
                softly.assertThat(
                                documents.findByWorkspaceIdAndIdForProjection(second.getWorkspaceId(), second.getId()))
                        .isPresent();
            for (var entryId : copiedEntries) {
                softly.assertThat(String.valueOf(entries.findByIdAndWorkspaceId(entryId, first.getWorkspaceId())
                                .orElseThrow()
                                .getPayload()))
                        .contains(COPIED_WORDS);
            }

            service.run(requestId);

            for (var document : selected) {
                var erased = documents.findById(document.getId()).orElseThrow();
                softly.assertThat(erased.getBodyMarkdown()).isNull();
                softly.assertThat(erased.getCreatedBySubject()).isNull();
                softly.assertThat(erased.getUpdatedBySubject()).isNull();
                softly.assertThat(erased.getCollaboratorSubjects()).isNullOrEmpty();
            }
            for (var entryId : copiedEntries) {
                var erased = entries.findByIdAndWorkspaceId(entryId, first.getWorkspaceId())
                        .orElseThrow();
                softly.assertThat(erased.isRedacted())
                        .as("conversation entry %s is redacted", entryId)
                        .isTrue();
                softly.assertThat(erased.getText()).isEmpty();
                softly.assertThat(erased.getSearchText()).isEmpty();
                softly.assertThat(String.valueOf(erased.getPayload())).doesNotContain(COPIED_WORDS);
            }
            softly.assertThat(service.get(requestId).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
            if (team != null)
                softly.assertThat(
                                documents.findByWorkspaceIdAndIdForProjection(second.getWorkspaceId(), second.getId()))
                        .hasValueSatisfying(document ->
                                assertThat(document.getBodyMarkdown()).isEqualTo(COPIED_WORDS));
            softly.assertThat(documents.findByWorkspaceIdAndIdForProjection(control.getWorkspaceId(), control.getId()))
                    .hasValueSatisfying(
                            document -> assertThat(document.getBodyMarkdown()).isEqualTo(COPIED_WORDS));
            for (var entryId : controlEntries) {
                var kept = entries.findByIdAndWorkspaceId(entryId, control.getWorkspaceId())
                        .orElseThrow();
                softly.assertThat(kept.isRedacted()).isFalse();
                softly.assertThat(String.valueOf(kept.getPayload())).contains(COPIED_WORDS);
            }
        });
    }

    private OutlineDocument teamDocument(IdentityProvider provider, String team, String subject) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("outline-" + UUID.randomUUID()));
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                team,
                new ConnectionConfig.OutlineConfig(provider.getServerUrl(), null, null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connection = connections.saveAndFlush(connection);
        var document = new OutlineDocument();
        document.setWorkspaceId(workspace.getId());
        document.setConnectionId(connection.getId());
        document.setDocumentId(UUID.randomUUID().toString());
        document.setCollectionId(UUID.randomUUID().toString());
        document.setTitle("Outline document");
        document.setBodyMarkdown(COPIED_WORDS);
        document.setCollaboratorSubjects(List.of(subject));
        return documents.saveAndFlush(document);
    }

    private List<UUID> copyInConversation(OutlineDocument document, long accountId) {
        long workspaceId = document.getWorkspaceId();
        var conversation = conversations.findOrCreateMain(workspaceId, accountId);
        String words = Objects.requireNonNull(document.getBodyMarkdown());
        var toolResult = conversationEntries.append(
                workspaceId,
                conversation.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.TOOL_RESULT,
                        ConversationEntry.Surface.APP,
                        "",
                        ConversationLedgerFixtures.toolResult(
                                ConversationLedgerFixtures.piId(), "copy", "fetch_context", words, false),
                        null,
                        null,
                        null));
        var answer = conversationEntries.append(
                workspaceId, conversation.getId(), ConversationLedgerFixtures.hephaestus(words));
        return List.of(toolResult.getId(), answer.getId());
    }

    @Test
    void exactEditorAndCollaboratorKeysCoverBodyCopiesWithoutExportingOtherProfiles() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OUTLINE, "https://outline-copy.example.test"));
        var admin = new Account("Administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        String target = "00000000-0000-4000-8000-000000000042";
        String other = "00000000-0000-4000-8000-000000000084";
        var rows = new SchemaRowSeeder(jdbc);
        rows.insert(
                "workspace",
                Map.of(
                        "id",
                        995701L,
                        "slug",
                        "outline-copies",
                        "display_name",
                        "Outline copies",
                        "status",
                        "ACTIVE",
                        "account_type",
                        "ORG"));
        rows.insert(
                "connection",
                Map.of(
                        "id",
                        995702L,
                        "workspace_id",
                        995701L,
                        "kind",
                        "OUTLINE",
                        "state",
                        "ACTIVE",
                        "config",
                        "{\"type\":\"OUTLINE\",\"serverUrl\":\"https://outline-copy.example.test\",\"enabledStreams\":[]}"));
        for (int n = 0; n < 3; n++) {
            var fields = new HashMap<String, Object>();
            fields.put("id", 995703L + n);
            fields.put("workspace_id", 995701L);
            fields.put("connection_id", 995702L);
            fields.put("document_id", UUID.randomUUID().toString());
            fields.put("collection_id", UUID.randomUUID().toString());
            fields.put("created_by_subject", other);
            fields.put("created_by_name", "Other profile canary");
            fields.put("updated_by_subject", n == 0 ? target : other);
            fields.put("updated_by_name", n == 0 ? "Target editor" : "Other editor canary");
            fields.put(
                    "collaborator_subjects", n == 1 ? "[\"" + target + "\",\"" + other + "\"]" : "[\"" + other + "\"]");
            fields.put("title", n < 2 ? "Shared target work" : "Unrelated work");
            fields.put("body_markdown", n < 2 ? "Target copied body" : "Unrelated body canary");
            rows.insert("outline_document", fields);
        }
        var preview = service.preview(
                adminId, null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), target, null)));
        var export = service.export(preview.request().getId()).path("stores");
        assertThat(export.path("outline_document_content").size()).isEqualTo(2);
        assertThat(export.path("outline_document_editor").size()).isEqualTo(1);
        assertThat(export.path("outline_document_collaborator").size()).isEqualTo(1);
        assertThat(export.toString())
                .contains("Target copied body")
                .doesNotContain("Other profile canary", "Other editor canary", "Unrelated body canary");
        service.requestErasure(preview.request().getId(), adminId, true);
        service.run(preview.request().getId());
        assertThat(service.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outline_document WHERE id IN (995703,995704) AND body_markdown IS NULL AND title IS NULL AND created_by_subject=?",
                        Long.class,
                        other))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT updated_by_subject IS NULL FROM outline_document WHERE id=995703", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT collaborator_subjects::text FROM outline_document WHERE id=995704", String.class))
                .isEqualTo("[\"" + other + "\"]");
        assertThat(jdbc.queryForObject("SELECT body_markdown FROM outline_document WHERE id=995705", String.class))
                .isEqualTo("Unrelated body canary");
        // Upstream sync is allowed to mirror the work again, but every AI projection stays suppressed.
        jdbc.update(
                "UPDATE outline_document SET updated_by_subject=?,body_markdown='Restored copied body' WHERE id=995703",
                target);
        jdbc.update(
                "UPDATE outline_document SET collaborator_subjects=CAST(? AS jsonb),body_markdown='Restored copied body' WHERE id=995704",
                "[\"" + target + "\",\"" + other + "\"]");
        assertThat(documents.findForProjection(995701L, Pageable.unpaged()))
                .extracting(OutlineDocument::getId)
                .containsExactly(995705L);
        assertThat(documents.findByWorkspaceIdAndIdForProjection(995701L, 995703L))
                .isEmpty();
        assertThat(documents.searchByRelevance(995701L, "body", 10))
                .extracting(OutlineDocument::getId)
                .containsExactly(995705L);
    }
}
