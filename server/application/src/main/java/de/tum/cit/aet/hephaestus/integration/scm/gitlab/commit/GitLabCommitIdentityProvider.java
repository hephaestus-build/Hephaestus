package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections.ActiveScm;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.ProviderCredentialPauseRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.CircuitBreakerOpenException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ActiveScmConnections connections;
    private final ProviderCredentialPauseRepository pauses;

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
    public Batch answer(
            String nameWithOwner, long providerId, long scopeId, ActiveScm initialCredential, Set<Question> questions) {
        Map<Question, Answer> answers = new HashMap<>();
        String serverUrl = Objects.requireNonNull(SourceProviderNamespaces.configuredUrl(initialCredential.config()));
        for (var question : questions) {
            var current = connections
                    .withToken(scopeId)
                    .filter(credential -> credential.kind() == IntegrationKind.GITLAB
                            && serverUrl.equals(SourceProviderNamespaces.configuredUrl(credential.config())));
            if (current.isEmpty()) {
                deferRest(questions, answers, new Answer.RetryAfterCredentialChange());
                break;
            }
            var credential = current.get();
            var generation = new CredentialGeneration(credential.connectionId(), credential.credentialsGeneration());
            var paused = pauses.pausedUntil(scopeId, generation.connectionId(), generation.generation());
            if (paused.isPresent()) {
                return new Batch(answers, new Pause(paused.get(), generation, true));
            }
            boolean readSucceeded = false;
            try {
                clients.acquirePermission();
                var exchange = new ProviderReply.Exchange();
                var response = clients.forCredential(scopeId, credential, exchange)
                        .documentName("GetCommitIdentity")
                        .variable("fullPath", nameWithOwner)
                        .variable("sha", question.sha())
                        .execute()
                        .block(properties.graphqlTimeout());
                var reply = response == null ? new ProviderReply.Failed("no response") : classify(exchange, response);
                if (reply instanceof ProviderReply.Throttled throttled) {
                    clients.recordSuccess();
                    return new Batch(answers, new Pause(throttled.until(), generation));
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
                return new Batch(answers, new Pause(Instant.now().plus(ProviderReply.UNNAMED_WAIT), generation));
            } catch (RuntimeException exception) {
                // A throttle is local to this credential. The circuit breaker is shared.
                var refusal = ProviderReply.refusalOf(exception);
                if (refusal != null) {
                    var reply = ProviderReply.classify(ProviderReply.signalOf(refusal), Instant.now());
                    if (reply instanceof ProviderReply.Throttled throttled) {
                        clients.recordSuccess();
                        return new Batch(answers, new Pause(throttled.until(), generation));
                    }
                    if (reply instanceof ProviderReply.AuthLost && !connections.isCurrent(scopeId, credential)) {
                        clients.recordSuccess();
                        deferRest(questions, answers, new Answer.RetryAfterCredentialChange());
                        break;
                    }
                }
                if (!readSucceeded) clients.recordFailure(exception);
                log.warn(
                        "GitLab commit identity read failed: providerId={}, cause={}",
                        providerId,
                        exception.getClass().getSimpleName());
            }
        }
        return new Batch(answers, null);
    }

    private static void deferRest(Set<Question> questions, Map<Question, Answer> answers, Answer deferred) {
        for (var pending : questions) {
            answers.putIfAbsent(pending, deferred);
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
}
