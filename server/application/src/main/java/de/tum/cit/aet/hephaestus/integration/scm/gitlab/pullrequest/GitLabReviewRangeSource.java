package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmReviewRangeSource;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(GitLabMergeRequestReadinessReader.class)
@RequiredArgsConstructor
public class GitLabReviewRangeSource implements ScmReviewRangeSource {
    private final GitLabMergeRequestReadinessReader reader;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public Optional<ReviewRange> read(long workspaceId, String repository, int number) {
        var facts = reader.read(workspaceId, repository, number);
        if (facts == null || facts.diffRefs() == null) return Optional.empty();
        var pair = facts.diffRefs();
        return Optional.of(
                new ReviewRange(facts.projectNativeId(), facts.mergeRequestNativeId(), pair.head(), pair.base()));
    }
}
