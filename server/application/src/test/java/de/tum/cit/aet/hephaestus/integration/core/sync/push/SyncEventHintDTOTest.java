package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SyncEventHintDTOTest extends BaseUnitTest {

    /**
     * The spec's scope values are an annotation constant, so they cannot be read from the enum; the stored module events
     * keep the enum's names, so the enum cannot carry the wire value as its JSON value either.
     */
    @Test
    void shouldDocumentEveryScopeThatTheStreamSends() throws NoSuchFieldException {
        var documented = SyncEventHintDTO.class.getDeclaredField("scope").getAnnotation(Schema.class).allowableValues();

        assertThat(documented)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(SyncStateChangedEvent.Scope.values())
                        .map(SyncStateChangedEvent.Scope::wireValue)
                        .toList());
    }
}
