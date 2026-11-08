package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.CircuitBreakerOpenException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reads GitLab's own author account for an email on a representative commit. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
public class GitLabCommitIdentityProvider implements CommitIdentityProvider {

    private final GitLabGraphQlClientProvider clients;
    private final GitLabProperties properties;
    private final GitLabUserService users;
    private final PersonProcessingSuppression suppression;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Actor(
            String id,
            String username,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String webUrl,
            @Nullable String publicEmail,
            @Nullable Boolean bot) {}

    record CommitNode(
            String sha,
            @Nullable String authorEmail,
            @Nullable Actor author) {}

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public Map<Question, Answer> answer(String nameWithOwner, long providerId, long scopeId, Set<Question> questions) {
        Map<Question, Answer> answers = new HashMap<>();
        for (var question : questions) {
            boolean readSucceeded = false;
            try {
                if (!clients.isCircuitClosed()
                        || clients.getRateLimitRemaining(scopeId) < GitLabSyncConstants.CRITICAL_REMAINING_THRESHOLD) {
                    answers.put(question, new Answer.Deferred());
                    continue;
                }
                clients.waitIfRateLimitLow(scopeId);
                clients.acquirePermission();
                var response = clients.forScope(scopeId)
                        .documentName("GetCommitIdentity")
                        .variable("fullPath", nameWithOwner)
                        .variable("sha", question.sha())
                        .execute()
                        .block(properties.graphqlTimeout());
                if (response == null
                        || !response.isValid()
                        || !response.getErrors().isEmpty()) {
                    clients.recordFailure(new IllegalStateException("GitLab did not answer a commit identity read"));
                    continue;
                }
                clients.recordSuccess();
                readSucceeded = true;
                var field = response.field("project.repository.commit");
                if (!(field.getValue() instanceof Map<?, ?> raw)
                        || !raw.containsKey("author")
                        || !raw.containsKey("authorEmail")) {
                    continue;
                }
                var commit = field.toEntity(CommitNode.class);
                if (commit == null
                        || !question.sha().equals(commit.sha())
                        || !question.email().equalsIgnoreCase(commit.authorEmail())) {
                    continue;
                }
                var actor = commit.author();
                if (actor == null) {
                    answers.put(question, new Answer.NoAccount());
                    continue;
                }
                long nativeId = GitLabSyncConstants.extractNumericId(actor.id());
                if (suppression.isSuppressed(providerId, Long.toString(nativeId), null)) {
                    answers.put(question, new Answer.Erased());
                    continue;
                }
                var local = users.findOrCreateUser(
                        new GitLabUserLookup(
                                actor.id(),
                                actor.username(),
                                actor.name(),
                                actor.avatarUrl(),
                                actor.webUrl(),
                                actor.publicEmail(),
                                actor.bot()),
                        providerId);
                if (local != null && local.getId() != null) {
                    answers.put(question, new Answer.Account(local.getId(), nativeId));
                }
            } catch (CircuitBreakerOpenException exception) {
                answers.put(question, new Answer.Deferred());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                for (var pending : questions) {
                    answers.putIfAbsent(pending, new Answer.Deferred());
                }
                break;
            } catch (RuntimeException exception) {
                if (!readSucceeded) clients.recordFailure(exception);
                log.warn(
                        "GitLab commit identity read failed: providerId={}, cause={}",
                        providerId,
                        exception.getClass().getSimpleName());
            }
        }
        return answers;
    }
}
