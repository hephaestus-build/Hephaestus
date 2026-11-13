package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.util.Objects;
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

    GitHubMemberMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubUserProcessor userProcessor,
            RepositoryCollaboratorRepository collaboratorRepository,
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
    }

    @Override
    protected void handleEvent(GitHubMemberEventDTO event) {
        var memberDto = event.member();

        if (memberDto == null) {
            log.warn("Received member event with missing data: action={}", event.action());
            return;
        }

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

        switch (event.actionType()) {
            case GitHubEventAction.Member.ADDED -> handleCollaboratorAdded(repository, user, event);
            case GitHubEventAction.Member.REMOVED -> handleCollaboratorRemoved(repository, user);
            default -> log.debug("Skipped member event: reason=unhandledAction, action={}", event.action());
        }
    }

    private void handleCollaboratorAdded(Repository repository, User user, GitHubMemberEventDTO event) {
        // Extract permission from event changes
        String permissionValue = event.getPermission();
        RepositoryCollaborator.Permission permission =
                RepositoryCollaborator.Permission.fromGitHubValue(permissionValue);

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
