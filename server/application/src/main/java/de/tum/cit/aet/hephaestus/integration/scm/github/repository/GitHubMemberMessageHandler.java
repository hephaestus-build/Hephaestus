package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubAccessReader;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubReaderRemovals;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub member webhook events (repository collaborator changes).
 */
@Component
public class GitHubMemberMessageHandler extends AbstractIntegrationMessageHandler<GitHubMemberEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubMemberMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubUserProcessor userProcessor;
    private final RepositoryCollaboratorRepository collaboratorRepository;
    private final ScopeIdResolver scopeIdResolver;
    private final GitHubAccessReader accessReader;
    private final GitHubReaderRemovals readerRemovals;

    GitHubMemberMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubUserProcessor userProcessor,
            RepositoryCollaboratorRepository collaboratorRepository,
            ScopeIdResolver scopeIdResolver,
            GitHubAccessReader accessReader,
            GitHubReaderRemovals readerRemovals,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.MEMBER.getValue(),
                GitHubMemberEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.userProcessor = userProcessor;
        this.collaboratorRepository = collaboratorRepository;
        this.scopeIdResolver = scopeIdResolver;
        this.accessReader = accessReader;
        this.readerRemovals = readerRemovals;
    }

    /**
     * The event names the repository and the person whose access changed; what is stored is the access GitHub reports
     * now, read before the write transaction. A delivery that arrives again after a later change cannot restore the
     * older access.
     */
    @Override
    protected void dispatchEvent(GitHubMemberEventDTO event) {
        Optional<CurrentAccess> current = currentAccess(event);
        if (current.isEmpty()) return;
        handleOnce(() -> apply(event, current.get()));
    }

    @Override
    protected void handleEvent(GitHubMemberEventDTO event) {
        throw new IllegalStateException("A member event reads GitHub before its write transaction; dispatch it");
    }

    /** The person's role on the repository as GitHub reports it now, or none. */
    private record CurrentAccess(@Nullable String role) {}

    /**
     * Reads the person's access without a transaction. Empty when the event cannot be handled: it lacks the person or
     * the repository, it is not an addition, change or removal, or no active workspace reads the repository. A removal that cannot be
     * read is applied, because removing is the safe direction.
     */
    private Optional<CurrentAccess> currentAccess(GitHubMemberEventDTO event) {
        var memberDto = event.member();
        var repositoryRef = event.repository();
        boolean removed = event.actionType() == GitHubEventAction.Member.REMOVED;
        if (memberDto == null || memberDto.id() == null || repositoryRef == null || repositoryRef.fullName() == null) {
            log.warn("Received member event with missing data: action={}", event.action());
            return Optional.empty();
        }
        if (!removed
                && event.actionType() != GitHubEventAction.Member.ADDED
                && event.actionType() != GitHubEventAction.Member.EDITED) {
            log.debug("Skipped member event: reason=unhandledAction, action={}", event.action());
            return Optional.empty();
        }
        String[] ownerAndName = repositoryRef.fullName().split("/", 2);
        Optional<Long> scopeId = scopeIdResolver.findScopeIdByRepositoryName(repositoryRef.fullName());
        if (ownerAndName.length != 2 || scopeId.isEmpty()) {
            log.debug("Skipped member event: reason=noWorkspace, action={}", event.action());
            return Optional.empty();
        }
        if (!accessReader.isReadable(scopeId.get())) {
            // A workspace that cannot read GitHub still applies a removal: removing access is the safe direction.
            log.debug("Member event without a GitHub read: reason=unreadable, action={}", event.action());
            return removed ? Optional.of(new CurrentAccess(null)) : Optional.empty();
        }
        try {
            return Optional.of(new CurrentAccess(accessReader
                    .collaboratorRole(scopeId.get(), ownerAndName[0], ownerAndName[1], memberDto.id())
                    .orElse(null)));
        } catch (RuntimeException readFailed) {
            if (!removed) throw readFailed;
            log.warn(
                    "Applied collaborator removal without reading GitHub: error={}",
                    readFailed.getClass().getSimpleName());
            return Optional.of(new CurrentAccess(null));
        }
    }

    private void apply(GitHubMemberEventDTO event, CurrentAccess current) {
        var memberDto = event.member();

        log.debug(
                "Received member event: action={}, userNativeId={}, repoName={}",
                event.action(),
                memberDto.getDatabaseId(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        // Ensure user exists via processor
        User user = userProcessor.ensureExists(memberDto, Objects.requireNonNull(context.providerId()));
        if (user == null) {
            log.warn("Skipped member event: reason=userNotFound, userNativeId={}", memberDto.getDatabaseId());
            return;
        }

        Repository repository = Objects.requireNonNull(context.repository());

        if (current.role() != null) {
            handleCollaboratorAdded(repository, user, current.role());
        } else {
            handleCollaboratorRemoved(repository, user);
            // Also when the mirror holds no row for the person: a listing that found them may not have stored it yet.
            readerRemovals.removedFromRepository(
                    Objects.requireNonNull(context.providerId()),
                    memberDto.getDatabaseId(),
                    event.repository() == null ? null : event.repository().id());
        }
    }

    private void handleCollaboratorAdded(Repository repository, User user, String role) {
        RepositoryCollaborator.Permission permission = RepositoryCollaborator.Permission.fromGitHubValue(role);

        // Check if collaborator already exists
        var existingCollaborator = collaboratorRepository.findByRepositoryIdAndUserId(repository.getId(), user.getId());

        if (existingCollaborator.isPresent()) {
            // Update permission if changed
            RepositoryCollaborator collaborator = existingCollaborator.get();
            collaborator.updatePermission(permission);
            collaboratorRepository.save(collaborator);
            log.info(
                    "Updated collaborator permission: userId={}, repoName={}, permission={}",
                    user.getId(),
                    sanitizeForLog(repository.getNameWithOwner()),
                    permission);
        } else {
            // Create new collaborator
            RepositoryCollaborator collaborator = new RepositoryCollaborator(repository, user, permission);
            collaboratorRepository.save(collaborator);
            log.info(
                    "Added collaborator: userId={}, repoName={}, permission={}",
                    user.getId(),
                    sanitizeForLog(repository.getNameWithOwner()),
                    permission);
        }
    }

    private void handleCollaboratorRemoved(Repository repository, User user) {
        var existingCollaborator = collaboratorRepository.findByRepositoryIdAndUserId(repository.getId(), user.getId());

        if (existingCollaborator.isPresent()) {
            collaboratorRepository.delete(existingCollaborator.get());
            log.info(
                    "Removed collaborator: userId={}, repoName={}",
                    user.getId(),
                    sanitizeForLog(repository.getNameWithOwner()));
        } else {
            log.debug(
                    "Skipped collaborator removal: reason=notFound, userId={}, repoName={}",
                    user.getId(),
                    sanitizeForLog(repository.getNameWithOwner()));
        }
    }
}
