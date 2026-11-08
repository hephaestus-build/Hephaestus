package de.tum.cit.aet.hephaestus.integration.core.spi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class RoleHolderTest extends BaseUnitTest {
    @Test
    void shouldRefuseContradictoryIdentityFacts() {
        assertThatThrownBy(() -> new RoleHolder(AboutRole.AUTHOR, new PersonIdentity(1, "42", null), true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
