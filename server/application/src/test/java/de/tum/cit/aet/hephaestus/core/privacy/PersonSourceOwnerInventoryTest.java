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
        assertThatThrownBy(() -> new PersonSuppressionService(
                        mock(JdbcTemplate.class), List.of(), mock(PersonDataCopyFence.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void shouldRejectDuplicateSourceAttributionOwnersBeforeProcessingCanStart() {
        var owner = mock(PersonSourceIdentityContributor.class);
        when(owner.artifactKinds())
                .thenReturn(Set.of("scm.issue", "scm.pull_request", "chat.conversation_thread", "docs.document"));
        assertThatThrownBy(() -> new PersonSuppressionService(
                        mock(JdbcTemplate.class), List.of(owner, owner), mock(PersonDataCopyFence.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }
}
