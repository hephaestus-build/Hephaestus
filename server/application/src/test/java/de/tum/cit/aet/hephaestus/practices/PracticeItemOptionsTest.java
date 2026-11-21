package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PracticeItemOptionsTest extends BaseUnitTest {
    @Test
    void shouldRefuseAnUnknownKindBeforeAuthoring() {
        var catalog = mock(ItemDescriptorRegistry.class);
        var kind = new ItemKind("custom.work");
        when(catalog.descriptorFor(kind)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new PracticeItemOptions(catalog).require(kind))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldLetTheFollowUpOfCarriedPracticesJoinTheQuietWindowAsAContinuingOccasion() {
        var kind = new ItemKind("scm.pull_request");
        assertThat(new PracticeItemOptions(mock(ItemDescriptorRegistry.class))
                        .occasion(kind, ItemEventName.carried(kind)))
                .isEqualTo(OccasionKind.CONTINUING);
    }
}
