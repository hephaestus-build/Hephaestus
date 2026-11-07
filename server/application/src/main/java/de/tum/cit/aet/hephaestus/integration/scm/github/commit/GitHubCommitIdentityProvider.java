package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.commit.CommitAuthorEnrichmentService.CommitActor;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads GitHub's answer from {@code GitActor.user}: GitHub states that it is the account of the actor's email. One
 * representative commit therefore answers for that email on the repository.
 */
@Component
@RequiredArgsConstructor
public class GitHubCommitIdentityProvider implements CommitIdentityProvider {

    private final CommitAuthorEnrichmentService commits;
    private final GitHubGraphQlClientProvider graphQlClientProvider;
    private final GitHubUserProcessor users;
    private final PersonProcessingSuppression suppression;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public Map<Question, Answer> answer(String nameWithOwner, long providerId, long scopeId, Set<Question> questions) {
        var shas = questions.stream().map(Question::sha).distinct().toList();
        var actors = commits.fetchCommitActors(nameWithOwner, scopeId, shas);
        boolean stopped = graphQlClientProvider.isRateLimitCritical(scopeId);
        Map<Question, Answer> answers = new HashMap<>();
        for (var question : questions) {
            var commitActors = actors.get(question.sha());
            if (commitActors == null) {
                if (stopped) answers.put(question, new Answer.Deferred());
                continue;
            }
            var answer = answer(providerId, question.email(), commitActors);
            if (answer != null) answers.put(question, answer);
        }
        return answers;
    }

    /** Actors with the same email must agree. If they do not, GitHub gave no single answer. */
    private @Nullable Answer answer(long providerId, String email, List<CommitActor> actors) {
        var matching = actors.stream()
                .filter(actor -> email.equalsIgnoreCase(actor.email()))
                .toList();
        if (matching.isEmpty() || !matching.stream().allMatch(CommitActor::answered)) {
            return null;
        }
        var accounts = matching.stream()
                .map(CommitActor::user)
                .map(user -> user == null ? null : user.getDatabaseId())
                .distinct()
                .toList();
        if (accounts.size() != 1) {
            return null;
        }
        GitHubUserDTO user = matching.getFirst().user();
        if (user == null) {
            return new Answer.NoAccount();
        }
        long nativeId = Objects.requireNonNull(user.getDatabaseId());
        if (suppression.isSuppressed(providerId, Long.toString(nativeId), null)) {
            return new Answer.Erased();
        }
        var local = users.ensureExists(user, providerId);
        return local == null ? null : new Answer.Account(Objects.requireNonNull(local.getId()), nativeId);
    }
}
