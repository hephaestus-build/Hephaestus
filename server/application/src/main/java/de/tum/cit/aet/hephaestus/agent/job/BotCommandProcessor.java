package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReactionSink;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Processes bot commands from MR comments (e.g., {@code /hephaestus review}).
 *
 * <p>Listens for {@link BotCommandReceivedEvent} published by
 * {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabNoteMessageHandler}
 * when a non-system MR comment matches a known command pattern. Runs asynchronously
 * to avoid blocking webhook processing.
 *
 * <p>On a recognized command, acknowledges with an eyes reaction — skipped while instance silent
 * mode is engaged — then processes it.
 *
 * <p>The command prefix is {@code /hephaestus} (case-insensitive). Supported commands:
 * <ul>
 *   <li>{@code /hephaestus review} — retrigger a practice review on the MR</li>
 * </ul>
 *
 * <h2>Who may command it</h2>
 * <p>Every command is authorized against the commenter through {@link ReviewRequestAuthority}, which owns
 * the rule and the identity it fails closed on.
 *
 * <h2>What the review is recorded as</h2>
 * <p>The command raises the kind's declared manual-request signal, and the run is filed as
 * {@link ObservationOrigin#MANUAL} — filing it as LIVE would mix a self-selected sample (people ask for
 * reviews of work they were already unsure of) into the population the trend line is read from.
 */
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class BotCommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(BotCommandProcessor.class);

    private final ManualReviewRequests manualReviewRequests;
    private final PullRequestRepository pullRequestRepository;
    private final UserRepository userRepository;
    private final WorkspaceResolver workspaceResolver;
    private final Map<IntegrationKind, ScmCommentReactionSink> reactionSinks;
    private final SilentModeQuery silentModeQuery;

    public BotCommandProcessor(
            ManualReviewRequests manualReviewRequests,
            PullRequestRepository pullRequestRepository,
            UserRepository userRepository,
            WorkspaceResolver workspaceResolver,
            List<ScmCommentReactionSink> reactionSinkList,
            SilentModeQuery silentModeQuery) {
        this.manualReviewRequests = manualReviewRequests;
        this.pullRequestRepository = pullRequestRepository;
        this.userRepository = userRepository;
        this.workspaceResolver = workspaceResolver;
        this.silentModeQuery = silentModeQuery;
        Map<IntegrationKind, ScmCommentReactionSink> map = new EnumMap<>(IntegrationKind.class);
        for (ScmCommentReactionSink sink : reactionSinkList) {
            map.put(sink.kind(), sink);
        }
        this.reactionSinks = map;
    }

    /**
     * No transaction, deliberately. {@link AgentJobService#submit} opens its own so the idempotency-key
     * race it absorbs rolls back that insert alone. Joined to an outer transaction, the same race would
     * mark the whole unit of work rollback-only — so a second person asking at the same moment as the
     * first would lose the ledger row recording that they asked, not just the duplicate job.
     */
    @Async
    @TransactionalEventListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onBotCommandReceived(BotCommandReceivedEvent event) {
        // The ack is itself an external write: while silenced it would promise a review that won't post.
        if (!silentModeQuery.isSilentModeEngaged()) {
            addEyesReaction(event);
        }

        processCommand(event);
    }

    private void processCommand(BotCommandReceivedEvent event) {
        String command = event.noteBody().strip().toLowerCase(Locale.ROOT);

        if (command.equals("/hephaestus review") || command.startsWith("/hephaestus review ")) {
            handleReviewCommand(event);
        } else {
            log.debug(
                    "Unknown bot command: command={}, repoId={}, mrNumber={}, author={}",
                    command,
                    event.repositoryId(),
                    event.mrNumber(),
                    event.noteAuthor());
        }
    }

    private void handleReviewCommand(BotCommandReceivedEvent event) {
        long repositoryId = event.repositoryId();
        int mrNumber = event.mrNumber();
        String noteAuthor = event.noteAuthor();
        try {
            // Found by (repo, number), then re-fetched with the association graph the gate needs.
            PullRequest stub = pullRequestRepository
                    .findByRepositoryIdAndNumber(repositoryId, mrNumber)
                    .orElse(null);
            if (stub == null) {
                log.warn(
                        "Bot command: PR not found, repoId={}, mrNumber={}, author={}",
                        repositoryId,
                        mrNumber,
                        noteAuthor);
                return;
            }

            PullRequest pr =
                    pullRequestRepository.findByIdWithAllForGate(stub.getId()).orElse(null);
            if (pr == null) {
                log.warn("Bot command: PR disappeared during re-fetch, prId={}", stub.getId());
                return;
            }

            if (pr.getHeadRefOid() == null || pr.getHeadRefName() == null || pr.getBaseRefName() == null) {
                log.warn(
                        "Bot command: missing branch info, prId={}, headRefOid={}, headRefName={}, baseRefName={}",
                        pr.getId(),
                        pr.getHeadRefOid(),
                        pr.getHeadRefName(),
                        pr.getBaseRefName());
                return;
            }

            if (pr.getState() == PullRequest.State.CLOSED
                    || pr.getState() == PullRequest.State.MERGED
                    || pr.isMerged()) {
                log.info("Bot command: skipping closed/merged PR, prId={}, state={}", pr.getId(), pr.getState());
                return;
            }

            // Resolved before anything is authorized or spent: the workspace is what the commenter's
            // standing is judged against and what the ledger row and the job belong to.
            Workspace workspace = workspaceResolver
                    .resolveForRepository(
                            pr.getRepository() != null ? pr.getRepository().getNameWithOwner() : null)
                    .orElse(null);
            if (workspace == null) {
                log.debug("Bot command: no workspace monitors this repository, prId={}", pr.getId());
                return;
            }

            // By the identity the provider knows them by, never by login — a login's owner can change it,
            // so authorizing on one authorizes whoever holds it now. Exactly one identity, never the
            // account's whole set: a comment carries no Hephaestus account, so an admin under a second
            // provider would otherwise be authorized by a link this comment does not prove they hold.
            List<User> commenter = userRepository
                    .findByNativeIdAndProviderId(event.authorNativeId(), event.providerId())
                    .map(List::of)
                    .orElseGet(List::of);

            ManualReviewOutcome outcome = manualReviewRequests.requestPullRequestReview(workspace, pr, commenter);
            switch (outcome.status()) {
                case SUBMITTED ->
                    log.info(
                            "Bot command: review triggered, jobId={}, prId={}, mrNumber={}, author={}",
                            outcome.jobId(),
                            pr.getId(),
                            mrNumber,
                            noteAuthor);
                case REFUSED ->
                    log.info(
                            "Bot command: no review, prId={}, mrNumber={}, author={}, reason={}",
                            pr.getId(),
                            mrNumber,
                            noteAuthor,
                            outcome.reason());
                // Logged at warn: a request from somebody with no standing on the artifact is the shape
                // an attempt to aim coaching at a colleague takes, and it should be visible as one.
                case FORBIDDEN ->
                    log.warn(
                            "Bot command: refused, the commenter is neither an actor on this merge request nor a "
                                    + "workspace admin, prId={}, mrNumber={}, author={}",
                            pr.getId(),
                            mrNumber,
                            noteAuthor);
            }
        } catch (Exception e) {
            log.error(
                    "Bot command: failed to process review, repoId={}, mrNumber={}, author={}",
                    repositoryId,
                    mrNumber,
                    noteAuthor,
                    e);
        }
    }

    /**
     * Add an eyes emoji reaction to the bot command note. Dispatches through the
     * {@link ScmCommentReactionSink} SPI keyed by the event's {@link IntegrationKind};
     * the publishing vendor adapter stamps its own kind, so a future GitHub or Bitbucket
     * publisher slots in without touching this class — no vendor constant is named here.
     */
    private void addEyesReaction(BotCommandReceivedEvent event) {
        if (event.commentId() == null || event.scopeId() == null) {
            return;
        }
        ScmCommentReactionSink sink = reactionSinks.get(event.kind());
        if (sink == null) {
            return;
        }
        sink.react(event.scopeId(), event.commentId(), "eyes");
    }
}
