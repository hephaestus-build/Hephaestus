package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A developer's rating of feedback, and every note except a disagreement's explanation, stay the developer's alone
 * (ADR 0047). A wire type that carries the rating, or the developer's full response, is therefore one that only the
 * developer reads. A workspace admin reads {@code FeedbackAnswerDTO}, which has neither.
 */
class FeedbackAnswerPrivacyArchTest extends HephaestusArchitectureTest {

    /** The developer's own response and the request that sets it. */
    private static final Set<String> CARRY_THE_RATING = Set.of("FeedbackResponseDTO", "FeedbackResponseRequestDTO");

    /** Reads of the developer's own feedback: their practice profile and their practice pages. */
    private static final Set<String> HOLD_THE_FULL_RESPONSE = Set.of("OwnFeedbackRefDTO", "PracticePageFeedbackDTO");

    @Test
    void onlyTheDevelopersOwnTypesCarryTheirRating() {
        assertThat(dtosWithAFieldOf(FeedbackUsefulness.class))
                .as("DTOs that carry a developer's rating of feedback, which no admin may read")
                .isSubsetOf(CARRY_THE_RATING);
    }

    @Test
    void onlyTheDevelopersOwnReadsHoldTheirFullResponse() {
        assertThat(dtosWithAFieldOf(FeedbackResponseDTO.class))
                .as("DTOs that hold a developer's full response; an admin's read uses FeedbackAnswerDTO")
                .isSubsetOf(HOLD_THE_FULL_RESPONSE);
    }

    private static List<String> dtosWithAFieldOf(Class<?> type) {
        return classes.stream()
                .filter(c -> c.getSimpleName().endsWith("DTO"))
                .filter(c -> c.getFields().stream().anyMatch(f -> f.getRawType().isEquivalentTo(type)))
                .map(JavaClass::getSimpleName)
                .sorted()
                .toList();
    }
}
