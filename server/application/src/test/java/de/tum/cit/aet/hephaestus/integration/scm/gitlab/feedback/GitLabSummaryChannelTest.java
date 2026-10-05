package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.ExistingSummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitLabSummaryChannelTest extends BaseUnitTest {

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    @Mock
    private GitLabMrResolver mrResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitLabSummaryChannel channel;

    @BeforeEach
    void setUp() {
        channel = new GitLabSummaryChannel(gitLabProvider, mrResolver, egressGuard);
    }

    @Test
    void postSummaryReturnsNoteId() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mockGitlabResponse("gid://gitlab/Note/789");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(target, new FeedbackContent("hello", "marker"));

        assertThat(handle).isNotNull();
        assertThat(handle.externalId()).isEqualTo("gid://gitlab/Note/789");
        assertThat(handle.url()).isEqualTo("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        verify(spec).variable("body", "hello\n\nmarker");
    }

    @Test
    void shouldBlockPostMutationWhenSilentModeIsEngaged() {
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("gitlab.post-summary");

        assertThatThrownBy(() -> channel.postSummary(gitlabTarget(), new FeedbackContent("body", "marker")))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(gitLabProvider, never()).forScope(anyLong());
    }

    @Test
    void shouldBlockUpdateMutationWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("gitlab.update-summary");

        assertThatThrownBy(() -> channel.updateSummary(
                        gitlabTarget(), "gid://gitlab/Note/789", new FeedbackContent("body", "marker")))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(gitLabProvider, never()).forScope(anyLong());
    }

    @Test
    void postSummaryEscapesSlashCommands() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mockGitlabResponse("gid://gitlab/Note/789");
        when(spec.execute()).thenReturn(Mono.just(response));

        channel.postSummary(target, new FeedbackContent("/approve please\nsome body", "marker"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(spec).variable(eq("body"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().toString()).contains("`/approve`");
    }

    @Test
    void updateSummaryEscapesSlashCommands() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField idField = mock(ClientResponseField.class);
        when(response.field("updateNote.note.id")).thenReturn(idField);
        ClientResponseField urlField = mock(ClientResponseField.class);
        lenient().when(response.field("updateNote.note.url")).thenReturn(urlField);
        lenient()
                .when(urlField.getValue())
                .thenReturn("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        when(idField.getValue()).thenReturn("gid://gitlab/Note/1");
        ClientResponseField errorsField = mock(ClientResponseField.class);
        lenient().when(response.field("updateNote.errors")).thenReturn(errorsField);
        lenient().when(errorsField.getValue()).thenReturn(List.of());
        when(response.getErrors()).thenReturn(List.of());
        when(spec.execute()).thenReturn(Mono.just(response));

        channel.updateSummary(target, "gid://gitlab/Note/1", new FeedbackContent("/merge now\nrest", "marker"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(spec).variable(eq("body"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue().toString()).contains("`/merge`");
    }

    @Test
    void escapeSlashCommands_leavesMidLineCommandsUntouched() {
        // MULTILINE anchors ^ to line-start, so only a line-start "/approve" is an action; mid-line text is untouched.
        assertThat(GitLabSummaryChannel.escapeSlashCommands("Please ask them to /approve it"))
                .isEqualTo("Please ask them to /approve it");
        assertThat(GitLabSummaryChannel.escapeSlashCommands("/approve\n/merge")).isEqualTo("`/approve`\n`/merge`");
    }

    @Test
    void throwsOnRateLimit() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);
        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("rate limit is critical");
        verify(gitLabProvider, never()).forScope(anyLong());
    }

    @Test
    void anIssueThatCannotBeResolvedIsNotSent() {
        when(mrResolver.resolveIssueGid(1L, "group/project", 7))
                .thenThrow(new FeedbackDeliveryException("Issue not found via GraphQL"));

        assertThatThrownBy(() -> channel.postSummary(gitlabIssueTarget(), new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("Issue not found");
        verify(gitLabProvider, never()).forScope(anyLong());
    }

    @Test
    void postSummaryWrapsTransportErrorAsFeedbackDeliveryException() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .isNotInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("createNote transport error");
    }

    @Test
    void throwsOnMutationErrors() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField errorsField = mock(ClientResponseField.class);
        when(response.field("createNote.errors")).thenReturn(errorsField);
        when(errorsField.getValue()).thenReturn(List.of("not allowed"));
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("createNote failed");
    }

    @Test
    void postSummaryRoutesIssueSubjectToIssueGid() {
        FeedbackTarget issueTarget =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project#7", null);
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolveIssueGid(1L, "group/project", 7)).thenReturn("gid://gitlab/Issue/7");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGitlabResponse("gid://gitlab/Note/555");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(issueTarget, new FeedbackContent("hi", "marker"));

        assertThat(handle.externalId()).isEqualTo("gid://gitlab/Note/555");
        assertThat(handle.url()).isEqualTo("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        verify(mrResolver).resolveIssueGid(1L, "group/project", 7);
        verify(spec).variable(eq("noteableId"), eq("gid://gitlab/Issue/7"));
    }

    @Test
    void updateSummaryEditsNoteInPlace() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField idField = mock(ClientResponseField.class);
        when(response.field("updateNote.note.id")).thenReturn(idField);
        ClientResponseField urlField = mock(ClientResponseField.class);
        lenient().when(response.field("updateNote.note.url")).thenReturn(urlField);
        lenient()
                .when(urlField.getValue())
                .thenReturn("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        when(idField.getValue()).thenReturn("gid://gitlab/Note/789");
        ClientResponseField errorsField = mock(ClientResponseField.class);
        lenient().when(response.field("updateNote.errors")).thenReturn(errorsField);
        lenient().when(errorsField.getValue()).thenReturn(List.of());
        when(response.getErrors()).thenReturn(List.of());
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryChannel.UpdateOutcome outcome =
                channel.updateSummary(target, "gid://gitlab/Note/789", new FeedbackContent("updated body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.EDITED);
        assertNotNull(outcome.handle());
        assertThat(outcome.handle().externalId()).isEqualTo("gid://gitlab/Note/789");
        assertThat(outcome.handle().url())
                .isEqualTo("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        verify(spec).variable(eq("id"), eq("gid://gitlab/Note/789"));
        verify(spec).variable(eq("body"), eq("updated body\n\nmarker"));
    }

    @Test
    void updateSummaryReturnsGoneOnNotFoundError() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        stubMutationErrors(List.of("note not found"));

        SummaryChannel.UpdateOutcome outcome =
                channel.updateSummary(target, "gid://gitlab/Note/gone", new FeedbackContent("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.GONE);
    }

    @Test
    void updateSummaryReturnsTransientOnGenericError() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        stubMutationErrors(List.of("something went wrong"));

        SummaryChannel.UpdateOutcome outcome =
                channel.updateSummary(target, "gid://gitlab/Note/1", new FeedbackContent("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.TRANSIENT);
    }

    @Test
    void updateSummaryReturnsTransientOnRateLimitCritical() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);

        SummaryChannel.UpdateOutcome outcome =
                channel.updateSummary(target, "gid://gitlab/Note/1", new FeedbackContent("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.TRANSIENT);
    }

    @Test
    void updateSummaryThrowsOnBlankExternalId() {
        FeedbackTarget target = gitlabTarget();
        assertThatThrownBy(() -> channel.updateSummary(target, "  ", new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("external note id is missing");
    }

    private void stubMutationErrors(List<String> errors) {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.getErrors()).thenReturn(List.of());
        ClientResponseField errorsField = mock(ClientResponseField.class);
        when(response.field("updateNote.errors")).thenReturn(errorsField);
        when(errorsField.getValue()).thenReturn(errors);
        when(spec.execute()).thenReturn(Mono.just(response));
    }

    /**
     * A deleted note has no {@code updateNote} payload — GitLab reports it as a top-level GraphQL error. This
     * orphaned-summary case must classify as GONE so the caller re-posts rather than silently dropping it.
     */
    @Test
    void updateSummaryReturnsGoneOnTopLevelNotFoundError() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ResponseError notFound = mock(ResponseError.class);
        when(notFound.getMessage())
                .thenReturn(
                        "The resource that you are attempting to access does not exist or you don't have permission to perform this action");
        when(response.getErrors()).thenReturn(List.of(notFound));
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryChannel.UpdateOutcome outcome =
                channel.updateSummary(target, "gid://gitlab/Note/4825166", new FeedbackContent("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.GONE);
    }

    private static final String MARKER = "<!-- hephaestus-summary:job-1 -->";

    private static final FeedbackContent EXPECTED = new FeedbackContent("/approve once fixed\nSummary text", MARKER);

    /** The exact body {@code postSummary} sends for {@link #EXPECTED}, slash command escaped. */
    private static final String EXACT = GitLabSummaryChannel.escapeSlashCommands(EXPECTED.externalBody());

    private static final String BOT = "gid://gitlab/User/1";

    private GraphQlClient.RequestSpec mockRequestChain() {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

    /**
     * Keyed by response path, so a test that stubs the MR path fails outright if the channel reads the issue path.
     * The page is read by {@link #BOT}.
     */
    private ClientGraphQlResponse mockNotesPage(
            String notesPath,
            List<Map<String, Object>> notes,
            boolean hasPreviousPage,
            @Nullable String startCursor,
            List<ResponseError> errors) {
        return mockNotesPage(notesPath, notes, hasPreviousPage, startCursor, errors, BOT);
    }

    private ClientGraphQlResponse mockNotesPage(
            String notesPath,
            List<Map<String, Object>> notes,
            boolean hasPreviousPage,
            @Nullable String startCursor,
            List<ResponseError> errors,
            @Nullable String currentUserId) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(errors);
        Map<String, Object> pageInfo = new HashMap<>();
        pageInfo.put("hasPreviousPage", hasPreviousPage);
        pageInfo.put("startCursor", startCursor);
        Map<String, Object> connection = Map.of("nodes", notes, "pageInfo", pageInfo);
        String document = notesPath.equals(ISSUE_NOTES_PATH) ? "GetIssueNotesNewest" : "GetMergeRequestNotesNewest";
        assertVendorCouldReturn(GITLAB, document, notesPath, connection);
        ClientResponseField connectionField = mock(ClientResponseField.class);
        lenient().when(response.field(notesPath)).thenReturn(connectionField);
        lenient().when(connectionField.getValue()).thenReturn(connection);
        ClientResponseField userField = mock(ClientResponseField.class);
        lenient().when(response.field("currentUser.id")).thenReturn(userField);
        lenient().when(userField.getValue()).thenReturn(currentUserId);
        return response;
    }

    private ClientGraphQlResponse mockMrNotesPage(
            List<Map<String, Object>> notes, boolean hasPreviousPage, @Nullable String startCursor) {
        return mockNotesPage(MR_NOTES_PATH, notes, hasPreviousPage, startCursor, List.of());
    }

    private static final String MR_NOTES_PATH = "project.mergeRequest.notes";
    private static final String ISSUE_NOTES_PATH = "project.issue.notes";

    /** A note the authenticated identity wrote. */
    private static Map<String, Object> note(String id, String body) {
        return note(id, body, BOT);
    }

    private static Map<String, Object> note(String id, String body, String authorId) {
        return Map.of(
                "id",
                id,
                "body",
                body,
                "url",
                "https://gitlab.example.com/group/project/-/merge_requests/42#note_987654",
                "author",
                Map.of("id", authorId));
    }

    @Test
    void findExistingSummary_choosesTheAuthoredExactCopyOverANewerHumanCopy() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(
                List.of(note("gid://gitlab/Note/1", EXACT), note("gid://gitlab/Note/2", EXACT, "gid://gitlab/User/2")),
                false,
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/1");
    }

    @Test
    void findExistingSummary_aMarkedNoteThatIsNotTheCopy_isUnknown_notFoundOrAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(
                List.of(
                        note("gid://gitlab/Note/1", EXPECTED.externalBody()),
                        note("gid://gitlab/Note/2", EXACT, "gid://gitlab/User/2")),
                false,
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        assertThat(channel.findExistingSummary(gitlabTarget(), EXPECTED).kind())
                .isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
    }

    @Test
    void findExistingSummary_withoutTheAuthenticatedIdentity_isUnknown_evenWithNothingMarked() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockNotesPage(MR_NOTES_PATH, List.of(), false, null, List.of(), null);
        when(spec.execute()).thenReturn(Mono.just(page));

        assertThat(channel.findExistingSummary(gitlabTarget(), EXPECTED).kind())
                .isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
    }

    @Test
    void findExistingSummary_matchOnFirstPage_isFound() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(
                List.of(note("gid://gitlab/Note/1", "a human said hi"), note("gid://gitlab/Note/2", EXACT)),
                false,
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.FOUND);
        // The handle is the note's own global id — exactly what updateSummary passes to UpdateNote as `id`.
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/2");
        assertThat(result.handle().url())
                .isEqualTo("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
    }

    /**
     * The just-posted summary is the newest note, so the newest end is requested first — no {@code before}
     * cursor, a {@code last} page size. A forward walk would only reach the marker after the whole thread.
     */
    @Test
    void findExistingSummary_walksTheNewestEndFirst() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(List.of(note("gid://gitlab/Note/9", EXACT)), true, "c");
        when(spec.execute()).thenReturn(Mono.just(page));

        channel.findExistingSummary(gitlabTarget(), EXPECTED);

        verify(spec).variable(eq("last"), eq(100));
        verify(spec).variable(eq("before"), eq(null));
        verify(spec).variable(eq("fullPath"), eq("group/project"));
        verify(spec).variable(eq("iid"), eq("42"));
    }

    @Test
    void findExistingSummary_everyNoteScanned_noMatch_isAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(
                List.of(note("gid://gitlab/Note/1", "unrelated")),
                false, // hasPreviousPage=false — the walk reached the oldest note, every note was seen
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.ABSENT);
    }

    @Test
    void findExistingSummary_pageBudgetExhaustedWithOlderNotesLeft_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page =
                mockMrNotesPage(List.of(note("gid://gitlab/Note/1", "unrelated")), true, "cursor-1");
        ClientGraphQlResponse page2 =
                mockMrNotesPage(List.of(note("gid://gitlab/Note/2", "unrelated")), true, "cursor-2");
        ClientGraphQlResponse page3 =
                mockMrNotesPage(List.of(note("gid://gitlab/Note/3", "unrelated")), true, "cursor-3");
        when(spec.execute())
                .thenReturn(Mono.just(page))
                .thenReturn(Mono.just(page2))
                .thenReturn(Mono.just(page3));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
        verify(spec, times(3)).execute();
    }

    @Test
    void findExistingSummary_blankStartCursorWithOlderNotesLeft_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(List.of(note("gid://gitlab/Note/1", "unrelated")), true, "  ");
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
        verify(spec, times(1)).execute();
    }

    @Test
    void findExistingSummary_secondPageHasTheMatch_isFound() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page1 =
                mockMrNotesPage(List.of(note("gid://gitlab/Note/1", "unrelated")), true, "cursor-1");
        ClientGraphQlResponse page2 = mockMrNotesPage(List.of(note("gid://gitlab/Note/2", EXACT)), false, null);
        when(spec.execute()).thenReturn(Mono.just(page1)).thenReturn(Mono.just(page2));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/2");
        assertThat(result.handle().url())
                .isEqualTo("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        verify(spec).variable(eq("before"), eq("cursor-1"));
    }

    @Test
    void findExistingSummary_topLevelGraphQlError_isUnknown_notAbsent() {
        // A fully-scanned, match-free page plus a top-level error must not read as confirmed absence.
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ResponseError error = mock(ResponseError.class);
        ClientGraphQlResponse page = mockNotesPage(
                MR_NOTES_PATH, List.of(note("gid://gitlab/Note/1", "unrelated")), false, null, List.of(error));
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
    }

    /**
     * An ISSUE subject ({@code path#iid}) must reach the issue's own notes; a lookup that only knew the
     * merge-request document would answer a permanent {@code UNKNOWN} and never find a summary posted on an issue.
     */
    @Test
    void findExistingSummary_issueSubject_scansIssueNotes_isFound() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        // Stubbed ONLY on the issue path: reading the merge-request path would yield null nodes → UNKNOWN.
        ClientGraphQlResponse page =
                mockNotesPage(ISSUE_NOTES_PATH, List.of(note("gid://gitlab/Note/7", EXACT)), false, null, List.of());
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabIssueTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/7");
        assertThat(result.handle().url())
                .isEqualTo("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        verify(client).documentName("GetIssueNotesNewest");
        verify(spec).variable(eq("fullPath"), eq("group/project"));
        verify(spec).variable(eq("iid"), eq("7"));
    }

    @Test
    void findExistingSummary_issueSubject_noMatch_isAbsent() {
        // Fail-closed applies only while absence is unproven; a fully-scanned thread with no marker is proven absence.
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page =
                mockNotesPage(ISSUE_NOTES_PATH, List.of(note("gid://gitlab/Note/1", "hi")), false, null, List.of());
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabIssueTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.ABSENT);
    }

    @Test
    void findExistingSummary_mergeRequestSubject_usesTheMergeRequestDocument() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse page = mockMrNotesPage(List.of(note("gid://gitlab/Note/3", EXACT)), false, null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.FOUND);
        verify(client).documentName("GetMergeRequestNotesNewest");
    }

    @Test
    void findExistingSummary_transportError_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        when(spec.execute()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
    }

    @Test
    void findExistingSummary_rateLimitCritical_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), EXPECTED);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
    }

    @Test
    void unreadableBodyPreventsAbsenceButDoesNotHideAnExactCopy() {
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse unreadable = mockMrNotesPage(List.of(Map.of("id", "gid://gitlab/Note/1")), false, null);
        ClientGraphQlResponse found = mockMrNotesPage(
                List.of(Map.of("id", "gid://gitlab/Note/1"), note("gid://gitlab/Note/2", EXACT)), false, null);
        when(spec.execute()).thenReturn(Mono.just(unreadable)).thenReturn(Mono.just(found));
        assertThat(channel.findExistingSummary(gitlabTarget(), EXPECTED).kind())
                .isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
        assertThat(channel.findExistingSummary(gitlabTarget(), EXPECTED).kind())
                .isEqualTo(ExistingSummaryLookup.Presence.FOUND);
    }

    @Test
    void blankNativeSummaryIdCannotAcknowledgeDelivery() {
        assertThatThrownBy(() -> new SummaryHandle(" ")).isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void findExistingSummary_blankMarker_isUnknown() {
        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), new FeedbackContent("body", "  "));

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Presence.UNKNOWN);
    }

    private static FeedbackTarget gitlabTarget() {
        return new FeedbackTarget(new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", null);
    }

    private static FeedbackTarget gitlabIssueTarget() {
        return new FeedbackTarget(new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project#7", null);
    }

    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockGitlabResponse(String noteId) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(List.of());
        ClientResponseField idField = mock(ClientResponseField.class);
        when(response.field("createNote.note.id")).thenReturn(idField);
        ClientResponseField urlField = mock(ClientResponseField.class);
        lenient().when(response.field("createNote.note.url")).thenReturn(urlField);
        lenient()
                .when(urlField.getValue())
                .thenReturn("https://gitlab.example.com/group/project/-/merge_requests/42#note_987654");
        when(idField.getValue()).thenReturn(noteId);
        ClientResponseField errorsField = mock(ClientResponseField.class);
        lenient().when(response.field("createNote.errors")).thenReturn(errorsField);
        lenient().when(errorsField.getValue()).thenReturn(List.of());
        return response;
    }

    @Test
    void postSummarySurfacesTopLevelError() {
        // A read-only GitLab instance returns no createNote payload, only a top-level error the channel must surface.
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ResponseError readOnly = mock(ResponseError.class);
        when(readOnly.getMessage()).thenReturn("You cannot perform write operations on a read-only instance");
        when(response.getErrors()).thenReturn(List.of(readOnly));
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("read-only instance");
    }
}
