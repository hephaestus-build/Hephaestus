package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadResolutionWriter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Resolves and reopens the GitLab discussion that an owned line note starts. */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabThreadResolutionWriter implements ThreadResolutionWriter {

    private final GitLabNotesApi http;
    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitLabTokenService tokenService;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;

    GitLabThreadResolutionWriter(
            GitLabNotesApi http,
            GitLabGraphQlClientProvider gitLabProvider,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets) {
        this.http = http;
        this.gitLabProvider = gitLabProvider;
        this.tokenService = tokenService;
        this.egressGuard = egressGuard;
        this.targets = targets;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public @Nullable String actor(FeedbackTarget target) {
        long workspaceId = target.item().workspaceId();
        try {
            String server = tokenService.resolveServerUrl(workspaceId);
            return Long.toString(http.currentUser(
                    workspaceId,
                    http.authorized(workspaceId, URI.create(server.replaceAll("/+$", "") + "/api/v4/user"))));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    // GitLab reports a reopened discussion without the account that reopened it, so Hephaestus's own reopen would
    // read as a person's. A problem that comes back reaches the developer through a new note instead.
    @Override
    public boolean reopensThreads() {
        return false;
    }

    // A discussion is resolved when its resolvable notes are; the owned note starts it, so its flag is the thread's.
    @Override
    public ThreadChange resolveThread(FeedbackTarget target, CopyReceipt copy, boolean resolved, String actor) {
        String subject = targets.subject(target, kind());
        long workspaceId = target.item().workspaceId();
        String externalRef = copy.externalRef();
        String thread = copy.threadRef();
        var ref = externalRef == null ? null : GitLabDiscussionNotes.NOTE_REF.matcher(externalRef);
        String segment = thread != null && thread.startsWith(GitLabDiscussionNotes.DISCUSSION_GID)
                ? thread.substring(GitLabDiscussionNotes.DISCUSSION_GID.length())
                : "";
        if (ref == null
                || !ref.matches()
                || !GitLabDiscussionNotes.SEGMENT.matcher(segment).matches())
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.NOT_OWNED, "Copy has no GitLab thread identity");
        if (gitLabProvider.isRateLimitCritical(workspaceId))
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "The GitLab rate limit is critical");
        String type = ref.group(1);
        long noteId = Long.parseLong(ref.group(2));
        try {
            MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(subject);
            String server = tokenService.resolveServerUrl(workspaceId);
            // The account that wrote the note is the account that resolves it: the actor recorded before this write.
            long author = Long.parseLong(actor);
            GitLabNotesApi.Answer read =
                    http.exchange(workspaceId, HttpMethod.GET, http.request(workspaceId, mr, segment), null);
            if (read.status() == 404
                    && GitLabDiscussionNotes.NOTE_ABSENT.matcher(read.text()).matches()) {
                GitLabNotesApi.Answer parent = http.exchange(
                        workspaceId,
                        HttpMethod.GET,
                        http.authorized(workspaceId, URI.create(GitLabDiffVersionReader.mergeRequestApi(server, mr))),
                        null);
                JsonNode readable = parent.status() == 200 ? parent.json() : null;
                return readable != null
                                && readable.path("iid").isIntegralNumber()
                                && readable.path("iid").longValue() == mr.iid()
                        ? ThreadChange.of(
                                copy.copyId(), ThreadChange.Outcome.GONE, "The provider thread no longer exists")
                        : ThreadChange.of(
                                copy.copyId(), ThreadChange.Outcome.TRANSIENT, "The merge request could not be read");
            }
            // A failed read says nothing about the thread: it is read again, never taken for a thread that is not ours.
            JsonNode found = read.status() == 200 ? read.json() : null;
            if (found == null)
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TRANSIENT, "The discussion could not be read");
            Boolean current = threadState(found, noteId, type, author);
            if (current == null)
                return ThreadChange.of(
                        copy.copyId(),
                        ThreadChange.Outcome.NOT_OWNED,
                        "The thread no longer starts with the owned note");
            if (resolvedByOther(found, author))
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TAKEN, "Another account resolved the thread");
            if (current == resolved) return ThreadChange.done(copy.copyId());
            if (resolved && repliedByOther(found, author))
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.REPLIED, "A person replied after the owned note");
            if (!egressGuard.deliveryAllowed(
                    resolved ? "gitlab.resolve-feedback-thread" : "gitlab.reopen-feedback-thread"))
                return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "Silent Mode holds the change");
            GitLabNotesApi.Answer written;
            try {
                written = http.exchange(
                        workspaceId,
                        HttpMethod.PUT,
                        http.request(workspaceId, mr, segment),
                        Map.of("resolved", resolved));
            } catch (OutboundEgressSuppressedException silenced) {
                throw silenced;
            } catch (RuntimeException unknown) {
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider thread change outcome is unknown");
            }
            JsonNode answer = written.status() == 200 ? written.json() : null;
            Boolean stored = answer == null ? null : threadState(answer, noteId, type, author);
            return Objects.equals(stored, resolved)
                    ? ThreadChange.done(copy.copyId())
                    : ThreadChange.of(
                            copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider did not confirm the thread state");
        } catch (OutboundEgressSuppressedException silenced) {
            // Silent Mode engaged between the check and the write: nothing was written.
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "Silent Mode holds the change");
        } catch (RuntimeException e) {
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider thread change failed");
        }
    }

    /**
     * Whether the discussion that starts with the owned note is resolved: every resolvable note is. GitLab resolves
     * notes one by one, so a resolved first note does not resolve the discussion. Null when the discussion does not
     * start with the owned note.
     */
    private static @Nullable Boolean threadState(JsonNode discussion, long noteId, String type, long author) {
        if (!discussion.path("notes").isArray() || discussion.path("notes").isEmpty()) return null;
        JsonNode first = discussion.path("notes").get(0);
        if (!GitLabDiscussionNotes.ownNote(first, noteId, type, author)
                || !first.path("resolvable").booleanValue()) return null;
        boolean resolved = true;
        for (JsonNode note : discussion.path("notes")) {
            if (!note.path("resolvable").booleanValue()) continue;
            if (!note.path("resolved").isBoolean()) return null;
            resolved &= note.path("resolved").booleanValue();
        }
        return resolved;
    }

    /** Whether another account resolved any note of the discussion: a person's state, which Hephaestus keeps. */
    private static boolean resolvedByOther(JsonNode discussion, long author) {
        for (JsonNode note : discussion.path("notes")) {
            if (!note.path("resolvable").booleanValue()
                    || !note.path("resolved").booleanValue()) continue;
            JsonNode resolver = note.path("resolved_by");
            if (!resolver.path("id").isIntegralNumber() || resolver.path("id").longValue() != author) return true;
        }
        return false;
    }

    // A person's reply anywhere after the owned note keeps the discussion open, also when a later note is the
    // account's own. A system note, such as "resolved all threads", is no reply.
    private static boolean repliedByOther(JsonNode discussion, long author) {
        var notes = discussion.path("notes");
        for (int index = 1; index < notes.size(); index++) {
            JsonNode note = notes.get(index);
            if (note.path("system").booleanValue()) continue;
            if (!note.path("author").path("id").isIntegralNumber()
                    || note.path("author").path("id").longValue() != author) return true;
        }
        return false;
    }
}
