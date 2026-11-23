package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.conversation.ConversationRejectedException;
import de.tum.cit.aet.hephaestus.core.exception.MentoringProblems;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProblemTypeCatalogTest {

    @Test
    void shouldGiveEveryRegisteredProblemAndConversationReasonCopyInBothLanguages() {
        var english = MentoringCopy.catalog("en");
        var german = MentoringCopy.catalog("de");
        verifyCatalogs(english, german);
        Stream.of(ConversationRefusal.values())
                .map(ConversationRefusal::label)
                .forEach(label -> assertThat(german).containsKey(label.code()));
        Stream.of(ConversationRejectedException.Reason.values())
                .map(ConversationRejectedException.Reason::label)
                .forEach(label -> assertThat(german).containsKey(label.code()));
    }

    @Test
    void shouldNameTheFieldAndSendACodedLabelWhenACheckInIsInvalid() {
        var problem = MentoringProblems.checkInInvalid("/wake");
        assertThat(problem.getType()).isEqualTo(ProblemType.CHECK_IN_INVALID.uri());
        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getProperties()).containsEntry("field", "/wake");
        assertThat(problem.getProperties())
                .hasEntrySatisfying(
                        "label",
                        value -> assertThat(value).isInstanceOfSatisfying(LabelDTO.class, label -> {
                            assertThat(label.code()).isEqualTo("problem.check-in-invalid");
                            assertThat(label.params())
                                    .containsEntry(
                                            "field", new LabelDTO.LabelParamDTO("value", "/wake", null, null, null));
                            assertThat(label.text()).isEqualTo(problem.getDetail());
                        }));
    }

    @Test
    void shouldRejectACatalogMissingARegisteredProblemOrALanguageKey() {
        var english = new HashMap<>(MentoringCopy.catalog("en"));
        var german = new HashMap<>(MentoringCopy.catalog("de"));
        var key = "problem." + ProblemType.CHECK_IN_INVALID.slug();
        english.remove(key);
        german.remove(key);
        assertThatThrownBy(() -> verifyCatalogs(english, german))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(key);

        english.put(key, "Try again.");
        assertThatThrownBy(() -> verifyCatalogs(english, german))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(key);
    }

    private static void verifyCatalogs(Map<String, String> english, Map<String, String> german) {
        assertThat(english.keySet()).containsExactlyInAnyOrderElementsOf(german.keySet());
        assertThat(english.values()).allSatisfy(text -> assertThat(text).isNotBlank());
        assertThat(german.values()).allSatisfy(text -> assertThat(text).isNotBlank());
        assertThat(english)
                .containsKeys(Stream.of(ProblemType.values())
                        .map(type -> "problem." + type.slug())
                        .toArray(String[]::new));
    }
}
