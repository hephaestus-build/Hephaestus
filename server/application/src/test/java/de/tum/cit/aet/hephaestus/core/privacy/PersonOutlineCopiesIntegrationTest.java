package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnContextStore;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OutlinePersonCopyScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.OutlineOriginBackfillChange;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.practices.groupdetail.ProductItemDirectoryQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.JsonNodeFactory;

@TestPropertySource(properties = "hephaestus.integration.outline.enabled=true")
class PersonOutlineCopiesIntegrationTest extends BaseIntegrationTest {
    private static final String COPIED_WORDS = "Outline collaborator canary";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private MembershipRepository memberships;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService conversationEntries;

    @Autowired
    private ConversationEntryRepository entries;

    private static final String ORIGIN = "https://outline-instance.example.test";
    private static final String OTHER_ORIGIN = "https://other-outline-instance.example.test";
    private static final String TEAM_ONE = "team-one";
    private static final String TEAM_TWO = "team-two";

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private ConversationTurnContextStore context;

    @Autowired
    private ProductItemDirectoryQuery itemDirectory;

    private enum Target {
        DOCUMENT,
        TOOL_RESULT,
        ANSWER,
        CONVERSATION_COPY,
        ITEM_DIRECTORY
    }

    private enum Key {
        INSTANCE,
        TEAM
    }

    private record Cell(Target target, Key key, boolean clearsSecondTeam) {}

    static Stream<Cell> stateTable() {
        return Stream.of(Target.values())
                .flatMap(target -> Stream.of(new Cell(target, Key.INSTANCE, true), new Cell(target, Key.TEAM, false)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stateTable")
    void outlineErasureStateTable(Cell cell) {
        var fixture = fixture(true);
        UUID request = admit(fixture, cell.key());

        for (var document : expectedErasure(fixture, cell)) {
            if (cell.target() == Target.DOCUMENT) assertHidden(document);
            if (cell.target() == Target.ITEM_DIRECTORY) assertDirectoryTitle(document, false);
        }
        assertThat(service.get(request).request().getState()).isEqualTo(PersonDataRequest.State.ERASING);

        service.run(request);

        for (var document : expectedErasure(fixture, cell)) assertErased(cell.target(), document);
        assertPreserved(cell.target(), fixture.control());
        if (!cell.clearsSecondTeam()) assertPreserved(cell.target(), fixture.second());
        assertThat(service.get(request).request().getState()).isEqualTo(PersonDataRequest.State.COMPLETE);
    }

    private static List<Example> expectedErasure(Fixture fixture, Cell cell) {
        return cell.clearsSecondTeam() ? List.of(fixture.first(), fixture.second()) : List.of(fixture.first());
    }

    @Test
    void outlineContextAcceptsArraysAndSqlNull() {
        var example = fixture(true).first();
        String update = "UPDATE conversation_turn SET outline_context=CAST(? AS jsonb) WHERE workspace_id=? AND id=?";
        long workspaceId = example.document().getWorkspaceId();
        UUID turnId = example.turnId();

        for (String invalid : List.of("{}", "null")) {
            assertThatThrownBy(() -> jdbc.update(update, invalid, workspaceId, turnId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_conversation_turn_outline_context_array");
        }
        assertThat(jdbc.update(update, "[]", workspaceId, turnId)).isOne();
        assertThat(jdbc.update(update, null, workspaceId, turnId)).isOne();
    }

    @Test
    void oldOutlineWritersCannotCreateAnUnselectableConnectionOrProvider() {
        var fixture = fixture(true);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO connection (workspace_id,kind,state,instance_key,config,created_at,updated_at,version)
                VALUES (?, 'OUTLINE','ACTIVE','late-team','{"type":"OUTLINE","serverUrl":"https://outline-instance.example.test/"}',now(),now(),0)
                """, fixture.first().document().getWorkspaceId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outline_connection_origin");
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO identity_provider(type,server_url,created_at) VALUES ('OUTLINE',?,now())",
                        "https://late-outline.example.test/"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outline_identity_origin");
    }

    @Test
    void upgradeBackfillsCanonicalKeysForStoredConnectionsAndAliases() throws Exception {
        var admin = PostgreSQLTestContainer.administrator();
        try (var connection = DriverManager.getConnection(admin.jdbcUrl(), admin.username(), admin.password());
                var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute(
                    "CREATE TEMP TABLE connection(id bigint,kind text,config jsonb,outline_origin text) ON COMMIT DROP");
            sql.execute(
                    "CREATE TEMP TABLE identity_provider(id bigint,type text,server_url text,outline_origin text) ON COMMIT DROP");
            sql.execute("""
                    INSERT INTO connection VALUES (1,'OUTLINE',
                        '{"serverUrl":"https://outline-instance.example.test/"}',null)
                    """);
            sql.execute("""
                    INSERT INTO identity_provider VALUES (2,'OUTLINE','HTTPS://OUTLINE-INSTANCE.EXAMPLE.TEST:443/',null)
                    """);
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            new OutlineOriginBackfillChange().execute(database);
            try (var keys = sql.executeQuery(
                    "SELECT outline_origin FROM connection UNION ALL SELECT outline_origin FROM identity_provider")) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getString(1)).isEqualTo(ORIGIN);
                assertThat(keys.next()).isTrue();
                assertThat(keys.getString(1)).isEqualTo(ORIGIN);
            }
            connection.commit();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLegacySelectionCannotCompleteEvenAfterAnAdministratorResumesIt(boolean resume) {
        var fixture = fixture(false);
        UUID request = admit(fixture, Key.INSTANCE);
        jdbc.update("""
                UPDATE person_data_request SET
                    scope_json=jsonb_set(scope_json::jsonb,'{outlineDocumentIds}','[]'::jsonb)::text,
                    selections_json=(SELECT jsonb_object_agg(key, '{"rows":[]}'::jsonb)
                        FROM jsonb_each(selections_json::jsonb))::text
                WHERE id=?
                """, request);
        service.run(request);
        if (resume) {
            service.requestErasure(request, fixture.admin(), true);
            service.run(request);
        }
        assertThat(service.get(request).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertThat(documents
                        .findById(fixture.first().document().getId())
                        .orElseThrow()
                        .getBodyMarkdown())
                .isEqualTo(COPIED_WORDS);
    }

    @Test
    void anEmptyDocumentSelectionCannotCompleteEvenWithTheRightScope() {
        var fixture = fixture(true);
        UUID request = admit(fixture, Key.INSTANCE);
        jdbc.update("""
                UPDATE person_data_request SET selections_json=jsonb_set(
                    selections_json::jsonb,'{outline_document_content}','{"rows":[]}'::jsonb)::text WHERE id=?
                """, request);
        service.run(request);
        assertThat(service.get(request).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertCopy(fixture.first(), fixture.first().answer(), false);
    }

    @Test
    void unknownCopyProvenanceStopsBeforeRedactingEitherInstance() {
        var fixture = fixture(true);
        jdbc.update(
                "UPDATE conversation_turn SET outline_context=NULL WHERE id=? AND workspace_id=?",
                fixture.control().turnId(),
                fixture.control().document().getWorkspaceId());
        UUID request = admit(fixture, Key.INSTANCE);
        service.run(request);
        assertThat(service.get(request).request().getState()).isEqualTo(PersonDataRequest.State.FAILED);
        assertCopy(fixture.first(), fixture.first().toolResult(), false);
        assertCopy(fixture.control(), fixture.control().toolResult(), false);
    }

    private void assertErased(Target target, Example example) {
        switch (target) {
            case DOCUMENT -> {
                var erased = documents.findById(example.document().getId()).orElseThrow();
                assertThat(erased.getBodyMarkdown()).isNull();
                assertThat(erased.getTitle()).isNull();
                assertThat(erased.getCreatedBySubject()).isNull();
                assertThat(erased.getUpdatedBySubject()).isNull();
                assertThat(erased.getCollaboratorSubjects()).isNullOrEmpty();
                refresh(example);
                assertHidden(example);
            }
            case ITEM_DIRECTORY -> {
                refresh(example);
                assertDirectoryTitle(example, false);
            }
            case TOOL_RESULT -> assertCopy(example, example.toolResult(), true);
            case ANSWER -> assertCopy(example, example.answer(), true);
            case CONVERSATION_COPY -> assertCopy(example, example.quote(), true);
        }
    }

    private void assertPreserved(Target target, Example example) {
        switch (target) {
            case DOCUMENT ->
                assertThat(documents.findByWorkspaceIdAndIdForProjection(
                                example.document().getWorkspaceId(),
                                example.document().getId()))
                        .hasValueSatisfying(document ->
                                assertThat(document.getBodyMarkdown()).isEqualTo(COPIED_WORDS));
            case ITEM_DIRECTORY -> assertDirectoryTitle(example, true);
            case TOOL_RESULT -> assertCopy(example, example.toolResult(), false);
            case ANSWER -> assertCopy(example, example.answer(), false);
            case CONVERSATION_COPY -> assertCopy(example, example.quote(), false);
        }
    }

    private void assertHidden(Example example) {
        var document = example.document();
        assertThat(documents.findByWorkspaceIdAndIdForProjection(document.getWorkspaceId(), document.getId()))
                .isEmpty();
        assertThat(documents.searchByRelevance(document.getWorkspaceId(), "canary", 10))
                .extracting(OutlineDocument::getId)
                .doesNotContain(document.getId());
    }

    private void assertDirectoryTitle(Example example, boolean visible) {
        var found =
                itemDirectory.list(example.document().getWorkspaceId(), "docs.document", Pageable.unpaged()).stream()
                        .filter(item -> item.id().equals(Long.toString(example.itemId())))
                        .findFirst()
                        .orElseThrow();
        assertThat(found.title()).isEqualTo(visible ? COPIED_WORDS : null);
    }

    private void refresh(Example example) {
        var current = documents.findById(example.document().getId()).orElseThrow();
        current.setTitle(COPIED_WORDS);
        current.setBodyMarkdown(COPIED_WORDS);
        current.setCollaboratorSubjects(example.document().getCollaboratorSubjects());
        documents.saveAndFlush(current);
    }

    private void assertCopy(Example example, UUID id, boolean erased) {
        var entry = entries.findByIdAndWorkspaceId(id, example.document().getWorkspaceId())
                .orElseThrow();
        assertThat(entry.isRedacted()).isEqualTo(erased);
        if (erased) {
            assertThat(entry.getText()).isEmpty();
            assertThat(entry.getSearchText()).isEmpty();
            assertThat(String.valueOf(entry.getPayload())).doesNotContain(COPIED_WORDS);
        } else {
            assertThat(String.valueOf(entry.getPayload())).contains(COPIED_WORDS);
        }
    }

    private UUID admit(Fixture fixture, Key key) {
        var identity = new PersonIdentity(fixture.provider(), fixture.subject(), key == Key.TEAM ? TEAM_ONE : null);
        UUID request = service.preview(fixture.admin(), null, List.of(identity))
                .request()
                .getId();
        service.requestErasure(request, fixture.admin(), true);
        return request;
    }

    private record Fixture(long provider, String subject, long admin, Example first, Example second, Example control) {}

    private record Example(
            OutlineDocument document, long itemId, UUID turnId, UUID toolResult, UUID answer, UUID quote) {}

    private Fixture fixture(boolean withCopies) {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.OUTLINE, ORIGIN));
        var otherProvider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.OUTLINE, OTHER_ORIGIN));
        var admin = new Account("Administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        var colleague = accounts.saveAndFlush(new Account("Colleague"));
        var link = new Identity();
        link.setAccount(colleague);
        link.setProviderId(Objects.requireNonNull(otherProvider.getId()));
        link.setProviderUserKey(UUID.randomUUID().toString());
        link.setTeamKey("team-control");
        identities.saveAndFlush(link);
        long colleagueId = Objects.requireNonNull(colleague.getId());
        String subject = UUID.randomUUID().toString();
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("outline-" + UUID.randomUUID()));
        var membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), colleagueId));
        memberships.saveAndFlush(membership);

        return new Fixture(
                Objects.requireNonNull(provider.getId()),
                subject,
                adminId,
                example(workspace, ORIGIN + "/", TEAM_ONE, subject, colleagueId, withCopies),
                example(workspace, ORIGIN, TEAM_TWO, subject, colleagueId, withCopies),
                example(workspace, OTHER_ORIGIN, "team-control", subject, colleagueId, withCopies));
    }

    private Example example(
            Workspace workspace, String url, String team, String subject, long accountId, boolean withCopies) {
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                team,
                new ConnectionConfig.OutlineConfig(url, null, null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connection = connections.saveAndFlush(connection);
        var document = new OutlineDocument();
        document.setWorkspaceId(workspace.getId());
        document.setConnectionId(connection.getId());
        document.setDocumentId(UUID.randomUUID().toString());
        document.setCollectionId(UUID.randomUUID().toString());
        document.setTitle(COPIED_WORDS);
        document.setBodyMarkdown(COPIED_WORDS);
        if (team.equals(TEAM_TWO)) {
            document.setCreatedBySubject(subject);
            document.setUpdatedBySubject(subject);
        }
        document.setCollaboratorSubjects(List.of(subject));
        documents.saveAndFlush(document);
        long itemId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO item (workspace_id,kind,source_id) VALUES (?,'docs.document',?) RETURNING id
                """, Long.class, workspace.getId(), document.getId()));
        return copyInConversation(document, connection, accountId, itemId, withCopies);
    }

    private Example copyInConversation(
            OutlineDocument document, Connection connection, long accountId, long itemId, boolean withCopies) {
        long workspaceId = document.getWorkspaceId();
        var conversation = conversations.findOrCreateMain(workspaceId, accountId);
        turns.submit(
                workspaceId,
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Read the document",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        var turn = claims.claimNext().orElseThrow();
        UUID run = Objects.requireNonNull(turn.getRunId());
        if (withCopies) {
            var source = new OutlinePersonCopyScope.Source(
                    Objects.requireNonNull(connection.getOutlineOrigin()),
                    Objects.requireNonNull(connection.getInstanceKey()));
            context.recordOutlineContext(workspaceId, turn.getId(), turn.getAttempt(), run, List.of(source));
        }
        var tool = ConversationLedgerFixtures.toolResult(
                ConversationLedgerFixtures.piId(), "copy", "read", COPIED_WORDS, false);
        var reply = ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), COPIED_WORDS);
        turns.end(
                workspaceId,
                turn.getId(),
                turn.getAttempt(),
                run,
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(tool, reply), null, null));
        UUID result = entries.findByWorkspaceIdAndTurnIdAndKind(
                        workspaceId, turn.getId(), ConversationEntry.Kind.TOOL_RESULT)
                .orElseThrow()
                .getId();
        UUID answer = entries.findByWorkspaceIdAndTurnIdAndKind(
                        workspaceId, turn.getId(), ConversationEntry.Kind.ANSWER)
                .orElseThrow()
                .getId();
        var quoteText = JsonNodeFactory.instance
                .objectNode()
                .put("entryId", answer.toString())
                .put("excerpt", COPIED_WORDS);
        var quote = conversationEntries.append(
                workspaceId,
                conversation.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.TOOL_RESULT,
                        ConversationEntry.Surface.APP,
                        "",
                        ConversationLedgerFixtures.toolResult(
                                ConversationLedgerFixtures.piId(),
                                "quote",
                                "search_conversation",
                                quoteText.toString(),
                                false),
                        null,
                        null,
                        null));
        return new Example(document, itemId, turn.getId(), result, answer, quote.getId());
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
                        "outline_origin",
                        "https://outline-copy.example.test",
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
        var export = PersonDataBundles.of(
                        service, transactionManager, preview.request().getId())
                .path("stores");
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
