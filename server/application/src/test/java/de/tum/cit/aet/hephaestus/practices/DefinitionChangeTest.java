package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefinitionChangeTest extends BaseUnitTest {
    private final PracticePrecondition gate = new PracticePrecondition(
            "the change has no Swift code",
            List.of(PracticePreconditionClause.changedPathMatches(List.of("**/*.swift"))));
    private final PracticeDefinition scoped = definition(ActorRole.REVIEWER, gate);
    private final PracticeDefinition unscoped = definition(ActorRole.AUTHOR, null);

    private PracticeDefinition definition(
            ActorRole subject, @org.jspecify.annotations.Nullable PracticePrecondition precondition) {
        return new PracticeDefinition(
                "Review work",
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                PracticeTestEvidence.needsFor(ScmSignals.PULL_REQUEST_OPENED.artifactKind()),
                Map.of(),
                subject,
                precondition,
                "Assess the review",
                PracticeJudgment.holistic(),
                null,
                PracticeTestEvidence.pullRequest(),
                null,
                null,
                null);
    }

    @Test
    void shouldRejectSilentRemovalOfGateAndSubject() {
        assertThatThrownBy(() -> DefinitionChange.requireExplicit(scoped, unscoped, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change when this practice applies");
        assertThatThrownBy(
                        () -> DefinitionChange.requireExplicit(scoped, unscoped, Set.of(DefinitionChange.PRECONDITION)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change whose work this practice reviews");
    }

    @Test
    void shouldAllowDeliberateRemoval() {
        assertThatCode(() -> DefinitionChange.requireExplicit(
                        scoped, unscoped, Set.of(DefinitionChange.PRECONDITION, DefinitionChange.SUBJECT)))
                .doesNotThrowAnyException();
    }
}
