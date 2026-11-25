package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.testconfig.SqlRequestTrace;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/** With no journal bucket configured, the journal stays in the database and no reply waits for a copy. */
@Tag("integration")
class PrivacyJournalWithoutBucketIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ObjectProvider<PrivacyJournalObjectStore> bucket;

    @Autowired
    private SqlRequestTrace requests;

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Test
    void shouldShipNothingAndAnswerAtOnceWhenNoBucketIsConfigured() {
        assertThat(bucket.getIfAvailable()).isNull();
        var user = persistUser("journal-local");
        var workspace = createWorkspace("journal-local", "Journal local", "journal-local", AccountType.ORG, user);
        ensureMembership(workspace, user, WorkspaceRole.OWNER);
        var account = accountId(user);
        var thread = conversations.findOrCreateMain(workspace.getId(), account);
        var entry = entries.append(
                workspace.getId(),
                thread.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.USER,
                        ConversationEntry.Surface.APP,
                        "Delete this question",
                        null,
                        null,
                        null,
                        null));

        var request = requests.measure(
                client,
                http -> http.delete()
                        .uri(
                                "/workspaces/{slug}/conversation/entries/{entryId}",
                                workspace.getWorkspaceSlug(),
                                entry.getId())
                        .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                        .exchange()
                        .expectStatus()
                        .isNoContent()
                        .expectBody(Void.class)
                        .returnResult());

        // The request writes its fact and reads the journal for nothing: a reply that confirms a fact reads its row.
        var journal = request.sql().stream()
                .filter(sql -> sql.contains("privacy_journal"))
                .toList();
        assertThat(journal).singleElement().asString().startsWith("INSERT INTO privacy_journal");
        // The fact is recorded for a later restore, but it needs no copy and nothing ships it.
        assertThat(jdbc.queryForList("""
                SELECT requires_shipping OR shipped_at IS NOT NULL FROM privacy_journal
                WHERE kind = 'ENTRY_DELETED' AND refs->>'entryId' = ?
                """, Boolean.class, entry.getId().toString()))
                .containsExactly(false);
    }
}
