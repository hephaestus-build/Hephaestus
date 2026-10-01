package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class RegisteredArtifactIdentitiesTest extends BaseUnitTest {

    private final RegisteredArtifactIdentities identities =
            new RegisteredArtifactIdentities(List.of(), PracticeSignalOptionsFixture.catalog());

    @Test
    void shouldNameWorkNoResolverCanNameByItsKindsDisplayName() {
        ArtifactIdentity identity = Objects.requireNonNull(
                identities.resolve(1L, ArtifactKinds.PULL_REQUEST, List.of(7L)).get(7L));

        assertThat(identity.title())
                .isEqualTo(PracticeSignalOptionsFixture.catalog().kindDisplayName(ArtifactKinds.PULL_REQUEST));
        assertThat(identity.provider()).isNull();
    }

    /** A title is what a reader is shown for the work, so an undeclared kind never surfaces its identifier. */
    @Test
    void shouldNameAKindNoModuleDeclaresGenericallyAndNeverByItsIdentifier() {
        ArtifactKind undeclared = ArtifactKind.of("tracker.ticket");

        ArtifactIdentity identity = Objects.requireNonNull(
                identities.resolve(1L, undeclared, List.of(9L)).get(9L));

        assertThat(identity.title()).isEqualTo("Other work").doesNotContain("tracker", "ticket");
    }
}
