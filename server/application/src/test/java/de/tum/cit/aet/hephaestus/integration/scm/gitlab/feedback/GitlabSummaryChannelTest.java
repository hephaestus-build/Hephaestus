package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.ExistingSummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabBackwardPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitlabMrResolver.MrInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitlabSummaryChannelTest extends BaseUnitTest {

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    @Mock
    private GitlabMrResolver mrResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitlabSummaryChannel channel;

    @BeforeEach
    void setUp() {
        channel = new GitlabSummaryChannel(gitLabProvider, mrResolver, egressGuard);
    }

    @Test
    void postSummaryReturnsNoteId() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mockGitlabResponse("gid://gitlab/Note/789");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(target, new FeedbackContent("hello", "marker"));

        assertThat(handle).isNotNull();
        assertThat(handle.externalId()).isEqualTo("gid://gitlab/Note/789");
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
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
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
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField idField = mock(ClientResponseField.class);
        when(response.field("updateNote.note.id")).thenReturn(idField);
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
        assertThat(GitlabSummaryChannel.escapeSlashCommands("Please ask them to /approve it"))
                .isEqualTo("Please ask them to /approve it");
        assertThat(GitlabSummaryChannel.escapeSlashCommands("/approve\n/merge")).isEqualTo("`/approve`\n`/merge`");
    }

    @Test
    void slashCommandPattern_matchesTheCanonicalPullRequestPosterLiteral() {
        // GitlabSummaryChannel.GITLAB_SLASH_COMMAND deliberately duplicates PullRequestCommentPoster's private
        // constant; this pins the copy against the canonical literal so the two cannot silently drift apart.
        String canonical = "^(\\s*/(?:approve|merge|close|reopen|assign|unassign|label|unlabel|lock|unlock|"
                + "milestone|estimate|spend|award|subscribe|unsubscribe|todo|done|wip|draft|ready|"
                + "due|remove_due_date|weight|epic|copy_metadata|move|confidential|shrug|tableflip)\\b)";
        assertThat(GitlabSummaryChannel.GITLAB_SLASH_COMMAND.pattern()).isEqualTo(canonical);
    }

    @Test
    void throwsOnRateLimit() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);
        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("rate limit critical");
    }

    @Test
    void postSummaryWrapsTransportErrorAsFeedbackDeliveryException() {
        // Must surface as FeedbackDeliveryException so PullRequestCommentPoster's catch-wrap stays uniform.
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("createNote transport error");
    }

    @Test
    void throwsOnMutationErrors() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(mrResolver.resolve(1L, "group/project", 42))
                .thenReturn(new MrInfo("gid://gitlab/MR/42", "base", "head", "start"));

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
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
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGitlabResponse("gid://gitlab/Note/555");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(issueTarget, new FeedbackContent("hi", "marker"));

        assertThat(handle.externalId()).isEqualTo("gid://gitlab/Note/555");
        verify(mrResolver).resolveIssueGid(1L, "group/project", 7);
        verify(spec).variable(eq("noteableId"), eq("gid://gitlab/Issue/7"));
    }

    @Test
    void updateSummaryEditsNoteInPlace() {
        FeedbackTarget target = gitlabTarget();
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField idField = mock(ClientResponseField.class);
        when(response.field("updateNote.note.id")).thenReturn(idField);
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
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
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
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
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

    private HttpGraphQlClient.RequestSpec mockRequestChain() {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

    /** Keyed by response path, so a test that stubs the MR path fails outright if the channel reads the issue path. */
    private ClientGraphQlResponse mockNotesPage(
            String notesPath,
            List<Map<String, Object>> notes,
            boolean hasPreviousPage,
            @Nullable String startCursor,
            List<ResponseError> errors) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(errors);
        ClientResponseField nodesField = mock(ClientResponseField.class);
        lenient().when(response.field(notesPath + ".nodes")).thenReturn(nodesField);
        lenient().when(nodesField.getValue()).thenReturn(notes);
        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        lenient().when(response.field(notesPath + ".pageInfo")).thenReturn(pageInfoField);
        lenient()
                .when(pageInfoField.toEntity(GitLabBackwardPageInfo.class))
                .thenReturn(new GitLabBackwardPageInfo(hasPreviousPage, startCursor));
        return response;
    }

    private ClientGraphQlResponse mockMrNotesPage(
            List<Map<String, Object>> notes, boolean hasPreviousPage, @Nullable String startCursor) {
        return mockNotesPage(MR_NOTES_PATH, notes, hasPreviousPage, startCursor, List.of());
    }

    private static final String MR_NOTES_PATH = "project.mergeRequest.notes";
    private static final String ISSUE_NOTES_PATH = "project.issue.notes";

    private static Map<String, Object> note(String id, String body) {
        return Map.of("id", id, "body", body);
    }

    @Test
    void findExistingSummary_matchOnFirstPage_isFound() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(
                List.of(
                        note("gid://gitlab/Note/1", "a human said hi"),
                        note("gid://gitlab/Note/2", MARKER + "\nsummary")),
                false,
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        // The handle is the note's own global id — exactly what updateSummary passes to UpdateNote as `id`.
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/2");
    }

    /**
     * The just-posted summary is the newest note, so the newest end is requested first — no {@code before}
     * cursor, a {@code last} page size. A forward walk would only reach the marker after the whole thread.
     */
    @Test
    void findExistingSummary_walksTheNewestEndFirst() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(List.of(note("gid://gitlab/Note/9", MARKER)), true, "c");
        when(spec.execute()).thenReturn(Mono.just(page));

        channel.findExistingSummary(gitlabTarget(), MARKER);

        verify(spec).variable(eq("last"), eq(100));
        verify(spec).variable(eq("before"), eq(null));
        verify(spec).variable(eq("fullPath"), eq("group/project"));
        verify(spec).variable(eq("iid"), eq("42"));
    }

    @Test
    void findExistingSummary_everyNoteScanned_noMatch_isAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(
                List.of(note("gid://gitlab/Note/1", "unrelated")),
                false, // hasPreviousPage=false — the walk reached the oldest note, every note was seen
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.ABSENT);
    }

    @Test
    void findExistingSummary_pageBudgetExhaustedWithOlderNotesLeft_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page =
                mockMrNotesPage(List.of(note("gid://gitlab/Note/1", "unrelated")), true, "cursor-1");
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
        verify(spec, org.mockito.Mockito.times(3)).execute();
    }

    @Test
    void findExistingSummary_blankStartCursorWithOlderNotesLeft_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockMrNotesPage(List.of(note("gid://gitlab/Note/1", "unrelated")), true, "  ");
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
        verify(spec, org.mockito.Mockito.times(1)).execute();
    }

    @Test
    void findExistingSummary_secondPageHasTheMatch_isFound() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page1 =
                mockMrNotesPage(List.of(note("gid://gitlab/Note/1", "unrelated")), true, "cursor-1");
        ClientGraphQlResponse page2 = mockMrNotesPage(List.of(note("gid://gitlab/Note/2", MARKER)), false, null);
        when(spec.execute()).thenReturn(Mono.just(page1)).thenReturn(Mono.just(page2));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/2");
        verify(spec).variable(eq("before"), eq("cursor-1"));
    }

    @Test
    void findExistingSummary_topLevelGraphQlError_isUnknown_notAbsent() {
        // A fully-scanned, match-free page plus a top-level error must not read as confirmed absence.
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ResponseError error = mock(ResponseError.class);
        ClientGraphQlResponse page = mockNotesPage(
                MR_NOTES_PATH, List.of(note("gid://gitlab/Note/1", "unrelated")), false, null, List.of(error));
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
    }

    /**
     * An ISSUE subject ({@code path#iid}) must reach the issue's own notes; a lookup that only knew the
     * merge-request document would answer a permanent {@code UNKNOWN} and never find a summary posted on an issue.
     */
    @Test
    void findExistingSummary_issueSubject_scansIssueNotes_isFound() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        // Stubbed ONLY on the issue path: reading the merge-request path would yield null nodes → UNKNOWN.
        ClientGraphQlResponse page = mockNotesPage(
                ISSUE_NOTES_PATH, List.of(note("gid://gitlab/Note/7", MARKER + "\nsummary")), false, null, List.of());
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabIssueTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("gid://gitlab/Note/7");
        verify(client).documentName("GetIssueNotesNewest");
        verify(spec).variable(eq("fullPath"), eq("group/project"));
        verify(spec).variable(eq("iid"), eq("7"));
    }

    @Test
    void findExistingSummary_issueSubject_noMatch_isAbsent() {
        // Fail-closed applies only while absence is unproven; a fully-scanned thread with no marker is proven absence.
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page =
                mockNotesPage(ISSUE_NOTES_PATH, List.of(note("gid://gitlab/Note/1", "hi")), false, null, List.of());
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabIssueTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.ABSENT);
    }

    @Test
    void findExistingSummary_mergeRequestSubject_usesTheMergeRequestDocument() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse page = mockMrNotesPage(List.of(note("gid://gitlab/Note/3", MARKER)), false, null);
        when(spec.execute()).thenReturn(Mono.just(page));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        verify(client).documentName("GetMergeRequestNotesNewest");
    }

    @Test
    void findExistingSummary_transportError_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        when(spec.execute()).thenReturn(Mono.error(new RuntimeException("connection reset")));

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
    }

    @Test
    void findExistingSummary_rateLimitCritical_isUnknown_notAbsent() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);

        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), MARKER);

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
    }

    @Test
    void findExistingSummary_blankMarker_isUnknown() {
        ExistingSummaryLookup result = channel.findExistingSummary(gitlabTarget(), "  ");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
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
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
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
