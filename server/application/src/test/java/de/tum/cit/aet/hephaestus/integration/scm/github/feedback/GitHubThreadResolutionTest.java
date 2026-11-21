package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/** The GitHub side of the thread resolution contract: read the thread, change it only when needed, confirm it. */
class GitHubThreadResolutionTest extends BaseUnitTest {
    private static final FeedbackTarget TARGET = ScmFeedbackFixtures.target(
            new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", "b".repeat(40));
    private static final String THREAD = "PRRT_1";
    private static final String NOTE = "PRRC_1";
    private static final String BOT = "41898282";

    private final GitHubGraphQlClientProvider provider = mock(GitHubGraphQlClientProvider.class);
    private final OutboundEgressGuard guard = mock(OutboundEgressGuard.class);
    private final List<String> mutations = new ArrayList<>();
    private @Nullable Map<String, Object> thread;
    private boolean confirms = true;
    private GitHubLineNoteWriter writer;

    @BeforeEach
    void setUp() {
        writer = new GitHubLineNoteWriter(
                provider,
                mock(GitHubPrNodeIdResolver.class),
                guard,
                ScmFeedbackFixtures.resolver(),
                ScmFeedbackFixtures.formatter());
        var client = mock(HttpGraphQlClient.class);
        lenient().when(provider.forScope(1L)).thenReturn(client);
        lenient().when(client.document(anyString())).thenAnswer(call -> {
            String document = call.getArgument(0);
            var request = mock(GraphQlClient.RequestSpec.class);
            lenient().when(request.variable(anyString(), any())).thenReturn(request);
            lenient().when(request.execute()).thenAnswer(execute -> Mono.just(answer(document)));
            return request;
        });
        thread = thread(false, NOTE, true);
        lenient().when(guard.deliveryAllowed(anyString())).thenReturn(true);
    }

    @Test
    void shouldResolveAnOwnedThreadAndConfirmItsState() {
        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(mutations).containsExactly("ResolveFeedbackThread");
        verify(guard).deliveryAllowed("github.resolve-feedback-thread");
    }

    @Test
    void shouldReopenAThreadItResolved() {
        thread = thread(true, NOTE, true);

        assertThat(writer.resolveThread(TARGET, copy(), false, BOT).outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(mutations).containsExactly("ReopenFeedbackThread");
        verify(guard).deliveryAllowed("github.reopen-feedback-thread");
    }

    @Test
    void shouldCountAThreadAlreadyInTheAskedStateAsDoneWithoutWriting() {
        thread = thread(true, NOTE, true);

        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(mutations).isEmpty();
        verify(guard, never()).deliveryAllowed(anyString());
    }

    @Test
    void shouldNotChangeAThreadThatDoesNotStartWithTheOwnedNote() {
        thread = thread(false, "PRRC_other", true);
        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.NOT_OWNED);

        thread = thread(false, NOTE, false);
        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.NOT_OWNED);
        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldNotResolveAThreadWhereAPersonRepliedAfterTheNoteButStillReopenOne() {
        thread = thread(false, NOTE, true, false);
        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.REPLIED);
        assertThat(mutations).isEmpty();

        thread = thread(true, NOTE, true, false);
        assertThat(writer.resolveThread(TARGET, copy(), false, BOT).outcome()).isEqualTo(ThreadChange.Outcome.DONE);
        assertThat(mutations).containsExactly("ReopenFeedbackThread");
    }

    @Test
    void shouldWriteNothingWhileSilentModeHoldsWrites() {
        when(guard.deliveryAllowed(anyString())).thenReturn(false);

        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.DEFERRED);
        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldNeitherReopenNorClaimAThreadThatAPersonResolved() {
        thread = thread(true, NOTE, true, true, 7L);
        assertThat(writer.resolveThread(TARGET, copy(), false, BOT).outcome()).isEqualTo(ThreadChange.Outcome.TAKEN);
        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.TAKEN);
        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldCountAResolveByTheTokensOwnUserAsItsOwn() {
        thread = thread(true, NOTE, true, true, Long.parseLong(BOT));

        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.DONE);
    }

    @Test
    void shouldReportARemovedThreadGone() {
        thread = null;

        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.GONE);
        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldLeaveAnUnconfirmedChangeTransient() {
        confirms = false;

        assertThat(writer.resolveThread(TARGET, copy(), true, BOT).outcome()).isEqualTo(ThreadChange.Outcome.TRANSIENT);
    }

    @Test
    void shouldActAsTheViewerOfItsToken() {
        assertThat(writer.actor(TARGET)).isEqualTo(BOT);
    }

    private static CopyReceipt copy() {
        return new CopyReceipt(UUID.randomUUID(), CopyDisposition.POSTED, NOTE, null, true, null, THREAD);
    }

    private static Map<String, Object> thread(boolean resolved, String firstNote, boolean ownFirst) {
        return thread(resolved, firstNote, ownFirst, ownFirst);
    }

    private static Map<String, Object> thread(boolean resolved, String firstNote, boolean ownFirst, boolean ownLatest) {
        return thread(resolved, firstNote, ownFirst, ownLatest, null);
    }

    /** {@code resolver} is the resolving User's id; a bot is no User, so its resolve names none. */
    private static Map<String, Object> thread(
            boolean resolved, String firstNote, boolean ownFirst, boolean ownLatest, @Nullable Long resolver) {
        var thread = new HashMap<String, Object>();
        thread.put("resolvedBy", resolver == null ? null : Map.of("databaseId", resolver));
        thread.putAll(Map.of(
                "__typename",
                "PullRequestReviewThread",
                "id",
                THREAD,
                "isResolved",
                resolved,
                "comments",
                Map.of("nodes", List.of(Map.of("id", firstNote, "viewerDidAuthor", ownFirst))),
                "latest",
                Map.of("nodes", List.of(Map.of("viewerDidAuthor", ownLatest)))));
        return thread;
    }

    private ClientGraphQlResponse answer(String document) {
        var fields = new HashMap<String, Object>();
        if (document.startsWith("query FeedbackThreadActor")) fields.put("viewer.databaseId", 41898282);
        else if (document.startsWith("query ReadFeedbackThread")) fields.put("node", thread);
        else {
            boolean resolve = document.startsWith("mutation ResolveFeedbackThread");
            mutations.add(resolve ? "ResolveFeedbackThread" : "ReopenFeedbackThread");
            String field = resolve ? "resolveReviewThread" : "unresolveReviewThread";
            fields.put(field + ".thread.id", THREAD);
            fields.put(field + ".thread.isResolved", confirms == resolve);
        }
        var response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(List.of());
        lenient().when(response.field(anyString())).thenAnswer(call -> {
            var field = mock(ClientResponseField.class);
            lenient().when(field.getValue()).thenReturn(fields.get(call.getArgument(0)));
            return field;
        });
        return response;
    }
}
