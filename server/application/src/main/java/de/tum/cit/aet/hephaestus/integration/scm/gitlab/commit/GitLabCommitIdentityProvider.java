package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.CircuitBreakerOpenException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
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
                    answers.put(question, new Answer.Deferred(deadline(scopeId)));
                    continue;
                }
                clients.waitIfRateLimitLow(scopeId);
                clients.acquirePermission();
                var exchange = new ProviderReply.Exchange();
                var response = clients.forScope(scopeId, exchange)
                        .documentName("GetCommitIdentity")
                        .variable("fullPath", nameWithOwner)
                        .variable("sha", question.sha())
                        .execute()
                        .block(properties.graphqlTimeout());
                var reply = response == null ? new ProviderReply.Failed("no response") : classify(exchange, response);
                if (reply instanceof ProviderReply.Throttled throttled) {
                    deferRest(questions, answers, throttled.until());
                    break;
                }
                if (response == null
                        || !(reply instanceof ProviderReply.Ok)
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
                answers.put(question, new Answer.Deferred(deadline(scopeId)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                for (var pending : questions) {
                    answers.putIfAbsent(pending, new Answer.Deferred(deadline(scopeId)));
                }
                break;
            } catch (RuntimeException exception) {
                if (!readSucceeded) clients.recordFailure(exception);
                // A refused read: a throttle in its status or headers is a wait, never a miss.
                var refusal = ProviderReply.refusalOf(exception);
                if (refusal != null
                        && ProviderReply.classify(ProviderReply.signalOf(refusal), Instant.now())
                                instanceof ProviderReply.Throttled throttled) {
                    deferRest(questions, answers, throttled.until());
                    break;
                }
                log.warn(
                        "GitLab commit identity read failed: providerId={}, cause={}",
                        providerId,
                        exception.getClass().getSimpleName());
            }
        }
        return answers;
    }

    /** A throttle answers nothing: this and every question still open wait for GitLab's deadline. */
    private static void deferRest(Set<Question> questions, Map<Question, Answer> answers, Instant until) {
        for (var pending : questions) {
            answers.putIfAbsent(pending, new Answer.Deferred(until));
        }
    }

    private static ProviderReply classify(ProviderReply.Exchange exchange, ClientGraphQlResponse response) {
        List<ProviderReply.BodyError> errors = response.getErrors().stream()
                .map(error -> new ProviderReply.BodyError(
                        error.getExtensions().get("code") instanceof String code ? code : null,
                        error.getMessage(),
                        false))
                .toList();
        int status = exchange.status() == 0 ? 200 : exchange.status();
        return ProviderReply.classify(
                new ProviderReply.Signal(status, exchange.headers(), errors, null, null), Instant.now());
    }

    /** GitLab's reset when it reported one, else a minute: a closed circuit or a low budget recovers soon. */
    private Instant deadline(long scopeId) {
        Instant reset = clients.getRateLimitResetAt(scopeId);
        Instant soon = Instant.now().plus(Duration.ofMinutes(1));
        return reset != null && reset.isAfter(soon) ? reset : soon;
    }
}
