package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab merge request webhook events.
 * <p>
 * Routes to {@link GitLabMergeRequestProcessor} based on the action:
 * <ul>
 *   <li>{@code open} / {@code update} → {@link GitLabMergeRequestProcessor#process}</li>
 *   <li>{@code close} → {@link GitLabMergeRequestProcessor#processClosed}</li>
 *   <li>{@code reopen} → {@link GitLabMergeRequestProcessor#processReopened}</li>
 *   <li>{@code merge} → {@link GitLabMergeRequestProcessor#processMerged}</li>
 *   <li>{@code approved} / {@code approval} → {@link GitLabMergeRequestProcessor#processApproved}</li>
 *   <li>{@code unapproved} / {@code unapproval} → {@link GitLabMergeRequestProcessor#processUnapproved}</li>
 * </ul>
 * Each pair is one person's act; the two names only say whether the merge request's approval rules were met
 * afterwards. An {@code unapproved} or {@code unapproval} marked {@code system} is GitLab resetting approvals after a
 * push instead.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMergeRequestMessageHandler extends AbstractIntegrationMessageHandler<GitLabMergeRequestEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestMessageHandler.class);

    /** The events after which the merge request's readiness is read: its head, status or approvals can have moved. */
    private static final Set<GitLabEventAction> READS_READINESS = EnumSet.of(
            GitLabEventAction.OPEN,
            GitLabEventAction.UPDATE,
            GitLabEventAction.REOPEN,
            GitLabEventAction.APPROVED,
            GitLabEventAction.APPROVAL,
            GitLabEventAction.UNAPPROVED,
            GitLabEventAction.UNAPPROVAL);

    private final GitLabMergeRequestProcessor mergeRequestProcessor;
    private final GitLabWebhookContextResolver contextResolver;
    private final GitLabClosingIssueClient closingIssueClient;
    private final GitLabMergeRequestReadinessReader readinessReader;
    private final TransactionTemplate transactionTemplate;

    GitLabMergeRequestMessageHandler(
            GitLabMergeRequestProcessor mergeRequestProcessor,
            GitLabWebhookContextResolver contextResolver,
            GitLabClosingIssueClient closingIssueClient,
            GitLabMergeRequestReadinessReader readinessReader,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.MERGE_REQUEST.getValue(),
                GitLabMergeRequestEventDTO.class,
                deserializer,
                transactionTemplate);
        this.mergeRequestProcessor = mergeRequestProcessor;
        this.contextResolver = contextResolver;
        this.closingIssueClient = closingIssueClient;
        this.readinessReader = readinessReader;
        this.transactionTemplate = transactionTemplate;
    }

    /** An event stored in its transaction, with the version of the merge request it left stored. */
    private record Stored(ProcessingContext context, GitLabMergeRequestProcessor.StoredVersion version) {}

    /**
     * The event is stored in the short transaction. GitLab's webhook carries none of the merge request's readiness —
     * its merge status, head pipeline and approvals — so after an event that can move them GitLab is read for this
     * one merge request, outside the transaction, and what it said is recorded in a second short one where it still
     * describes the stored head ({@link GitLabMergeRequestProcessor#applyReadiness}). A failed read records nothing:
     * the facts stay as the event left them, unknown where it moved the head, until the next event or sync. An opened
     * or updated merge request also has the issues it closes read from GitLab — the webhook stores the
     * {@code updated_at} the sync later compares against, so the sync would not read them for this change.
     */
    @Override
    protected void dispatchEvent(GitLabMergeRequestEventDTO event, Instant arrivedAt) {
        var attributes = event.objectAttributes();
        var project = event.project();
        Stored stored = transactionTemplate.execute(status -> {
            ProcessingContext context = handle(event, arrivedAt);
            if (context == null || context.repository() == null || attributes == null || attributes.iid() == null) {
                return null;
            }
            return mergeRequestProcessor
                    .storedVersion(context.repository(), attributes.iid())
                    .map(version -> new Stored(context, version))
                    .orElse(null);
        });
        GitLabEventAction action = event.actionType();
        if (stored == null || !READS_READINESS.contains(action) || attributes == null || attributes.iid() == null) {
            return;
        }
        Repository repository = Objects.requireNonNull(stored.context().repository());
        Long scopeId = stored.context().scopeId();
        if (scopeId == null) {
            return;
        }
        int iid = attributes.iid();
        Instant requestedAt = Instant.now();
        GitLabMergeRequestReadinessReader.Facts facts =
                readinessReader.read(scopeId, repository.getNameWithOwner(), iid);
        List<Integer> closing = (action == GitLabEventAction.OPEN || action == GitLabEventAction.UPDATE)
                        && project != null
                        && project.id() != null
                ? closingIssueClient.closesIssues(scopeId, project.id(), iid)
                : null;
        if (facts == null && closing == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (!contextResolver.mayStillWrite()) {
                return;
            }
            if (closing != null) {
                mergeRequestProcessor.replaceClosingIssues(repository, iid, closing, stored.version());
            }
            if (facts != null) {
                mergeRequestProcessor.applyReadiness(repository, iid, facts, requestedAt, stored.context());
            }
        });
    }

    @Override
    protected void handleEvent(GitLabMergeRequestEventDTO event) {
        handle(event, Instant.now());
    }

    /** Stores the event Hephaestus received at {@code arrivedAt}. */
    @Nullable
    ProcessingContext handle(GitLabMergeRequestEventDTO event, Instant arrivedAt) {
        if (event.objectAttributes() == null) {
            log.warn("Received merge request event with missing object_attributes");
            return null;
        }

        if (event.project() == null) {
            log.warn("Received merge request event with missing project data");
            return null;
        }

        if (event.isConfidential()) {
            log.debug(
                    "Skipped confidential merge request event: iid={}",
                    event.objectAttributes().iid());
            return null;
        }

        String projectPath = event.project().pathWithNamespace();
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("Received merge request event with missing project path");
            return null;
        }
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        GitLabEventAction action = event.actionType();

        log.info(
                "Processing merge request event: projectPath={}, iid={}, action={}",
                safeProjectPath,
                event.objectAttributes().iid(),
                action);

        ProcessingContext resolved = contextResolver.resolve(projectPath, action.getValue(), "merge request");
        if (resolved == null) {
            return null;
        }
        ProcessingContext context = resolved.withObservedAt(arrivedAt);

        switch (action) {
            case OPEN, UPDATE -> mergeRequestProcessor.process(event, context);
            case CLOSE -> mergeRequestProcessor.processClosed(event, context);
            case REOPEN -> mergeRequestProcessor.processReopened(event, context);
            case MERGE -> mergeRequestProcessor.processMerged(event, context);
            case APPROVED, APPROVAL -> mergeRequestProcessor.processApproved(event, context);
            case UNAPPROVED, UNAPPROVAL -> mergeRequestProcessor.processUnapproved(event, context);
            default -> log.debug("Unhandled merge request action: projectPath={}, action={}", safeProjectPath, action);
        }
        return context;
    }
}
