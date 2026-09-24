package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Hibernate's parameter scanner can interpret apostrophes in SQL line comments as unmatched
 * quotes. Check both query and count-query annotations before repository initialization.
 */
class NativeQueryCommentArchTest extends HephaestusArchitectureTest {

    // ArchUnit exposes directly declared annotations, so @NativeQuery must be matched separately.
    private static final Set<String> QUERY_ANNOTATIONS = Set.of(
            "org.springframework.data.jpa.repository.Query", "org.springframework.data.jpa.repository.NativeQuery");

    private static final Set<String> QUERY_ATTRIBUTES = Set.of("value", "countQuery");

    @Test
    void noApostrophesInSqlLineCommentsOfQueries() {
        ArchRule rule = methods()
                .that(new DescribedPredicate<JavaMethod>("are annotated with @Query or @NativeQuery") {
                    @Override
                    public boolean test(JavaMethod method) {
                        return !queryStrings(method).isEmpty();
                    }
                })
                .should(new ArchCondition<JavaMethod>("not contain an apostrophe in any -- SQL comment") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        queryStrings(method).forEach((attribute, query) -> {
                            for (String line : query.split("\\R")) {
                                int commentStart = line.indexOf("--");
                                if (commentStart < 0) {
                                    continue;
                                }
                                String comment = line.substring(commentStart);
                                if (comment.indexOf('\'') < 0) {
                                    continue;
                                }
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        "Apostrophe in a SQL line comment of the '" + attribute
                                                + "' query on "
                                                + method.getFullName()
                                                + " — Hibernate reads it as an unterminated quoted range and the "
                                                + "ApplicationContext will fail to start. Reword the comment. Offending line: "
                                                + line.trim()));
                            }
                        });
                    }
                })
                .allowEmptyShould(true);

        rule.check(classes);
    }

    /**
     * Every query string carried by the method, keyed by the attribute it came from. Empty when the
     * method declares no query annotation, or declares one whose query is defined some other way
     * (a named query, or a defaulted {@code countQuery}).
     */
    private static Map<String, String> queryStrings(JavaMethod method) {
        Map<String, String> found = new LinkedHashMap<>();
        method.getAnnotations().stream()
                .filter(annotation ->
                        QUERY_ANNOTATIONS.contains(annotation.getRawType().getName()))
                .forEach(annotation -> {
                    for (String attribute : QUERY_ATTRIBUTES) {
                        annotation
                                .get(attribute)
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .filter(query -> !query.isBlank())
                                .ifPresent(query -> found.put(attribute, query));
                    }
                });
        return found;
    }
}
