package de.tum.cit.aet.hephaestus.conversation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR 0164: a person shares one turn with one instance admin, sees every read, and can end the share. */
class SupportShareIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String QUESTION = "Why does my build fail on the second run?";
    private static final String ANSWER = "Your test reads a real host, so the second run waits for DNS.";

    @Autowired
    private WebTestClient client;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SupportShareRepository repository;

    @Autowired
    private SupportShareService service;

    private record Turn(Workspace workspace, long person, long admin, UUID turn, UUID question) {
        String slug() {
            return workspace.getWorkspaceSlug();
        }
    }

    @Test
    void shouldShowTheRecipientExactlyThePreviewAndCountEachRead() {
        var turn = turn("share-exact");

        SupportSharePreviewDTO preview = preview(turn, true);
        assertThat(preview.messages())
                .extracting(SupportShareMessageDTO::author, SupportShareMessageDTO::text)
                .containsExactly(
                        tuple(SupportShareMessageDTO.Author.QUESTION, QUESTION),
                        tuple(SupportShareMessageDTO.Author.ANSWER, ANSWER));
        assertThat(preview.turn().turnId()).isEqualTo(turn.turn());
        assertThat(preview.recipients())
                .extracting(SupportShareRecipientDTO::accountId)
                .contains(Long.toString(turn.admin()));
        assertThat(preview.validForDays()).isEqualTo(7);

        SupportShareDTO share = share(turn, true, preview.textDigest());
        assertThat(share.state()).isEqualTo(SupportShareDTO.State.ACTIVE);
        assertThat(share.readCount()).isZero();

        SupportShareReceivedDTO read = read(turn.admin(), share.id());
        assertThat(read.messages()).isEqualTo(preview.messages());
        assertThat(Objects.requireNonNull(read.turn()).turnId()).isEqualTo(turn.turn());
        assertThat(read.textWithheld()).isFalse();
        read(turn.admin(), share.id());

        assertThat(own(turn.person()))
                .filteredOn(mine -> mine.id().equals(share.id()))
                .singleElement()
                .satisfies(mine -> {
                    assertThat(mine.readCount()).isEqualTo(2);
                    assertThat(mine.lastReadAt()).isNotNull();
                });
        assertThat(received(turn.admin()))
                .filteredOn(listed -> listed.id().equals(share.id()))
                .singleElement()
                .satisfies(listed -> assertThat(listed.messages()).isEmpty());
    }

    @Test
    void shouldRefuseAShareWhoseMessagesChangedAfterThePreview() {
        var turn = turn("share-changed");
        String digest = Objects.requireNonNull(preview(turn, true).textDigest());

        appendAnswer(turn, "One more answer after the preview.");

        client.post()
                .uri("/workspaces/{slug}/support-shares/turns/{turn}", turn.slug(), turn.turn())
                .headers(h -> h.setBearerAuth(member(turn.person())))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(turn.admin(), true, digest))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
    }

    @Test
    void shouldWithholdTheTextWhenThePersonDeletesTheirMessage() {
        var turn = turn("share-deleted");
        SupportShareDTO share = share(turn, true, preview(turn, true).textDigest());

        client.delete()
                .uri("/workspaces/{slug}/conversation/entries/{entry}", turn.slug(), turn.question())
                .headers(h -> h.setBearerAuth(member(turn.person())))
                .exchange()
                .expectStatus()
                .is2xxSuccessful()
                .expectBody(Void.class);

        SupportShareReceivedDTO read = read(turn.admin(), share.id());
        assertThat(read.textWithheld()).isTrue();
        assertThat(read.messages()).isEmpty();
        assertThat(read.turn()).isNotNull();
    }

    @Test
    void shouldShowOnlyTheDiagnosticsWhenThePersonSharesNoText() {
        var turn = turn("share-diagnostics");
        SupportSharePreviewDTO preview = preview(turn, false);
        assertThat(preview.messages()).isEmpty();
        assertThat(preview.textDigest()).isNull();

        SupportShareReceivedDTO read =
                read(turn.admin(), share(turn, false, null).id());
        assertThat(read.messages()).isEmpty();
        assertThat(read.textWithheld()).isFalse();
        assertThat(Objects.requireNonNull(read.turn()).turnId()).isEqualTo(turn.turn());
    }

    @Test
    void shouldServeNoShareAfterTheRevocation() {
        var turn = turn("share-revoked");
        SupportShareDTO share = share(turn, true, preview(turn, true).textDigest());

        SupportShareDTO revoked = Objects.requireNonNull(client.delete()
                .uri("/user/support-shares/{id}", share.id())
                .headers(h -> h.setBearerAuth(member(turn.person())))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(SupportShareDTO.class)
                .returnResult()
                .getResponseBody());
        assertThat(revoked.state()).isEqualTo(SupportShareDTO.State.REVOKED);

        readStatus(turn.admin(), share.id()).isNotFound().expectBody(Void.class);
        assertThat(received(turn.admin()))
                .extracting(SupportShareReceivedDTO::id)
                .doesNotContain(share.id());
        assertThat(own(turn.person()))
                .filteredOn(mine -> mine.id().equals(share.id()))
                .singleElement()
                .satisfies(mine -> assertThat(mine.readCount()).isZero());
    }

    @Test
    void shouldKeepAReadThatTheRecipientCountsWhileThePersonRevokes() {
        var turn = turn("share-revoke-read");
        SupportShareDTO share = share(turn, true, preview(turn, true).textDigest());

        // The revoking transaction holds the share as it was; the recipient's read commits before the revocation.
        transactions.executeWithoutResult(status -> {
            assertThat(repository.findById(share.id()).orElseThrow().getReadCount())
                    .isZero();
            CompletableFuture.runAsync(() -> transactions.executeWithoutResult(
                            read -> repository.markRead(share.id(), turn.admin(), Instant.now())))
                    .join();
            service.revoke(turn.person(), share.id());
        });

        assertThat(own(turn.person()))
                .filteredOn(mine -> mine.id().equals(share.id()))
                .singleElement()
                .satisfies(mine -> {
                    assertThat(mine.state()).isEqualTo(SupportShareDTO.State.REVOKED);
                    assertThat(mine.readCount()).isOne();
                });
    }

    @Test
    void shouldKeepAnActiveShareRevocableAfterMoreRecentSharesHaveEnded() {
        var turn = turn("share-settings-history");
        SupportShareDTO active = share(turn, true, preview(turn, true).textDigest());
        jdbc.update("UPDATE support_share SET created_at = now() - interval '1 day' WHERE id = ?", active.id());
        jdbc.update("""
                INSERT INTO support_share (id, account_id, recipient_account_id, workspace_id, turn_id,
                                           created_at, expires_at, revoked_at)
                SELECT gen_random_uuid(), account_id, recipient_account_id, workspace_id, turn_id,
                       now(), now() + interval '7 days', now()
                FROM support_share CROSS JOIN generate_series(1, 50) WHERE id = ?
                """, active.id());

        assertThat(own(turn.person()))
                .filteredOn(share -> share.id().equals(active.id()))
                .singleElement()
                .satisfies(share -> assertThat(share.state()).isEqualTo(SupportShareDTO.State.ACTIVE));
    }

    @Test
    void shouldServeNoShareAfterItsExpiry() {
        var turn = turn("share-expired");
        SupportShareDTO share = share(turn, true, preview(turn, true).textDigest());
        jdbc.update(
                "UPDATE support_share SET created_at = now() - interval '8 days', expires_at = now() - interval '1 day'"
                        + " WHERE id = ?",
                share.id());

        readStatus(turn.admin(), share.id()).isNotFound().expectBody(Void.class);
        assertThat(own(turn.person()))
                .filteredOn(mine -> mine.id().equals(share.id()))
                .singleElement()
                .satisfies(mine -> assertThat(mine.state()).isEqualTo(SupportShareDTO.State.EXPIRED));
    }

    @Test
    void shouldServeAShareOnlyToItsRecipient() {
        var turn = turn("share-recipient");
        SupportShareDTO share = share(turn, true, preview(turn, true).textDigest());
        long otherAdmin = Objects.requireNonNull(
                persistInstanceAdmin("Other support admin").getId());

        readStatus(otherAdmin, share.id()).isNotFound().expectBody(Void.class);
        assertThat(received(otherAdmin)).extracting(SupportShareReceivedDTO::id).doesNotContain(share.id());
        client.get()
                .uri("/admin/support-shares/{id}", share.id())
                .headers(h -> h.setBearerAuth(member(turn.person())))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(own(turn.person()))
                .filteredOn(mine -> mine.id().equals(share.id()))
                .singleElement()
                .satisfies(mine -> assertThat(mine.readCount()).isZero());
    }

    @Test
    void shouldNotLetAnotherMemberShareOrPreviewAPersonsTurn() {
        var turn = turn("share-foreign");
        var colleague = persistUser("share-foreign-colleague");
        ensureMembership(turn.workspace(), colleague, WorkspaceRole.MEMBER);

        client.post()
                .uri("/workspaces/{slug}/support-shares/turns/{turn}/preview", turn.slug(), turn.turn())
                .headers(h -> h.setBearerAuth(member(accountId(colleague))))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("includeText", true))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    private Turn turn(String slug) {
        var developer = persistUser(slug + "-developer");
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, developer);
        ensureOwnerMembership(workspace);
        ensureMembership(workspace, developer, WorkspaceRole.MEMBER);
        long person = accountId(developer);
        long admin =
                Objects.requireNonNull(persistInstanceAdmin(slug + " support").getId());
        UUID[] ids = Objects.requireNonNull(transactions.execute(tx -> {
            owners.ofJob(person);
            var conversation = conversations.findOrCreateMain(workspace.getId(), person);
            var asked = turns.submit(
                    workspace.getId(),
                    conversation.getId(),
                    new ConversationTurnService.Question(
                            ConversationTurn.Surface.APP,
                            QUESTION,
                            UUID.randomUUID().toString(),
                            null,
                            null));
            UUID turnId = asked.turn().getId();
            entries.append(workspace.getId(), conversation.getId(), answer(ANSWER, turnId));
            return new UUID[] {turnId, asked.question().getId()};
        }));
        return new Turn(workspace, person, admin, ids[0], ids[1]);
    }

    private void appendAnswer(Turn turn, String text) {
        long workspaceId = turn.workspace().getId();
        transactions.executeWithoutResult(tx -> {
            owners.ofJob(turn.person());
            var conversation = conversations.findOrCreateMain(workspaceId, turn.person());
            entries.append(workspaceId, conversation.getId(), answer(text, turn.turn()));
        });
    }

    private static ConversationEntryDraft answer(String text, UUID turnId) {
        return new ConversationEntryDraft(
                ConversationEntry.Kind.ANSWER,
                ConversationEntry.Surface.APP,
                text,
                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), text),
                turnId,
                null,
                null);
    }

    private SupportSharePreviewDTO preview(Turn turn, boolean includeText) {
        return Objects.requireNonNull(client.post()
                .uri("/workspaces/{slug}/support-shares/turns/{turn}/preview", turn.slug(), turn.turn())
                .headers(h -> h.setBearerAuth(member(turn.person())))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("includeText", includeText))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(SupportSharePreviewDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private SupportShareDTO share(Turn turn, boolean includeText, @Nullable String digest) {
        return Objects.requireNonNull(client.post()
                .uri("/workspaces/{slug}/support-shares/turns/{turn}", turn.slug(), turn.turn())
                .headers(h -> h.setBearerAuth(member(turn.person())))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(turn.admin(), includeText, digest))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(SupportShareDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private static Map<String, Object> body(long admin, boolean includeText, @Nullable String digest) {
        return digest == null
                ? Map.of("recipientAccountId", Long.toString(admin), "includeText", includeText)
                : Map.of("recipientAccountId", Long.toString(admin), "includeText", includeText, "textDigest", digest);
    }

    private SupportShareReceivedDTO read(long admin, UUID share) {
        return Objects.requireNonNull(readStatus(admin, share)
                .isOk()
                .expectBody(SupportShareReceivedDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private StatusAssertions readStatus(long admin, UUID share) {
        return client.get()
                .uri("/admin/support-shares/{id}", share)
                .headers(h -> h.setBearerAuth("mock-jwt-admin-" + admin))
                .exchange()
                .expectStatus();
    }

    private List<SupportShareReceivedDTO> received(long admin) {
        return Objects.requireNonNull(client.get()
                .uri("/admin/support-shares")
                .headers(h -> h.setBearerAuth("mock-jwt-admin-" + admin))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(SupportShareReceivedDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private List<SupportShareDTO> own(long person) {
        return Objects.requireNonNull(client.get()
                .uri("/user/support-shares")
                .headers(h -> h.setBearerAuth(member(person)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(SupportShareDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private static String member(long account) {
        return "mock-jwt-member-" + account;
    }
}
