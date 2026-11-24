package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonSourceOwnerInventoryTest extends BaseUnitTest {
    @Test
    void shouldRejectMissingSourceAttributionOwnersBeforeProcessingCanStart() {
        assertThatThrownBy(() -> new IdentityRestrictionService(
                                mock(JdbcTemplate.class),
                                List.of(),
                                mock(PersonDataCopyFence.class),
                                (kind, refs) -> {})
                        .isSourceSuppressed(1L, "chat.thread", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attribution owner");
    }

    @Test
    void shouldRejectDuplicateSourceAttributionOwnersBeforeProcessingCanStart() {
        var owner = mock(PersonSourceIdentityContributor.class);
        when(owner.itemKinds()).thenReturn(Set.of("scm.issue", "scm.pull_request", "chat.thread", "docs.document"));
        assertThatThrownBy(() -> new IdentityRestrictionService(
                        mock(JdbcTemplate.class),
                        List.of(owner, owner),
                        mock(PersonDataCopyFence.class),
                        (kind, refs) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }
}
