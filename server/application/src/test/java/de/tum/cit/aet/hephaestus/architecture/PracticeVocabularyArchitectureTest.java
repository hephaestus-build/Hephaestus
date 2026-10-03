package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.GENERATED_GRAPHQL_PACKAGE;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No production class, field or method is named with a word the product vocabulary retired for an observation, so
 * the code cannot keep a concept alive that the copy and the API no longer have.
 */
class PracticeVocabularyArchitectureTest extends HephaestusArchitectureTest {

    private static final Pattern RETIRED_OBSERVATION_WORDS =
            Pattern.compile("finding|detection", Pattern.CASE_INSENSITIVE);

    @Test
    void identifiersDoNotUseTheRetiredObservationWords() {
        // The generated GitLab client keeps the schema's own words (SecurityFinding, SecretDetection).
        classes()
                .that()
                .resideOutsideOfPackage(GENERATED_GRAPHQL_PACKAGE)
                .should(notBeNamedWithARetiredObservationWord())
                .because("docs/contributor/practice-feedback-language.md retires finding and detection for an "
                        + "observation, in Java as everywhere else")
                .check(classes);
    }

    private static ArchCondition<JavaClass> notBeNamedWithARetiredObservationWord() {
        return new ArchCondition<>("not name the class, a field or a method with finding or detection") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (RETIRED_OBSERVATION_WORDS.matcher(javaClass.getSimpleName()).find()) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " is named with a retired observation word"));
                }
                Stream.<JavaMember>concat(javaClass.getFields().stream(), javaClass.getMethods().stream())
                        .filter(member -> !member.getModifiers().contains(JavaModifier.SYNTHETIC))
                        .filter(member -> RETIRED_OBSERVATION_WORDS
                                .matcher(member.getName())
                                .find())
                        .forEach(member -> events.add(SimpleConditionEvent.violated(
                                member, member.getFullName() + " is named with a retired observation word")));
            }
        };
    }
}
