package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BindingChangeTest extends BaseUnitTest {
    private final PracticeSubject gate = new PracticeSubject(
            "the change has no Swift code", List.of(PracticeSubjectClause.changedPathMatches(List.of("**/*.swift"))));
    private final PracticeBinding scoped =
            new PracticeBinding(List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(), false, ActorRole.REVIEWER, gate);
    private final PracticeBinding unscoped =
            new PracticeBinding(List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(), false, ActorRole.AUTHOR, null);

    @Test
    void shouldRejectSilentRemovalOfGateAndSubject() {
        assertThatThrownBy(() -> BindingChange.requireExplicit(List.of(scoped), List.of(unscoped), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPLIES_WHEN");
        assertThatThrownBy(() -> BindingChange.requireExplicit(
                        List.of(scoped), List.of(unscoped), Set.of(BindingChange.APPLIES_WHEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUBJECT");
    }

    @Test
    void shouldAllowDeliberateRemoval() {
        assertThatCode(() -> BindingChange.requireExplicit(
                        List.of(scoped), List.of(unscoped), Set.of(BindingChange.APPLIES_WHEN, BindingChange.SUBJECT)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldProtectAStoredLegacyBindingWhenItsOccasionsAreCollapsed() {
        assertThatThrownBy(() -> BindingChange.requireExplicit(List.of(scoped, unscoped), List.of(unscoped), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPLIES_WHEN");
    }
}
