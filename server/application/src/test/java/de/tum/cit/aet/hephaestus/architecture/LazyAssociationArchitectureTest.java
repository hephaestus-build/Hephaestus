package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every association is lazy. An eager association loads with each read of its owner, in every persistence context,
 * whether the caller needs it or not. JPA makes a to-one association eager unless it declares {@code LAZY}.
 */
class LazyAssociationArchitectureTest extends HephaestusArchitectureTest {

    @Test
    void associationsAreLazy() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage(BASE_PACKAGE + "..")
                .should(beLazy())
                .because("a read selects what it shows, and a write fetches what it needs in its query"
                        + " (docs/contributor/read-and-write-the-database.mdx)")
                .check(classes);
    }

    private static ArchCondition<JavaField> beLazy() {
        return new ArchCondition<>("declare no eager association") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (fetch(field) == FetchType.EAGER) {
                    events.add(SimpleConditionEvent.violated(
                            field, field.getFullName() + " is an eager association; declare fetch = FetchType.LAZY"));
                }
            }
        };
    }

    private static @Nullable FetchType fetch(JavaField field) {
        if (field.isAnnotatedWith(ManyToOne.class))
            return field.getAnnotationOfType(ManyToOne.class).fetch();
        if (field.isAnnotatedWith(OneToOne.class))
            return field.getAnnotationOfType(OneToOne.class).fetch();
        if (field.isAnnotatedWith(OneToMany.class))
            return field.getAnnotationOfType(OneToMany.class).fetch();
        if (field.isAnnotatedWith(ManyToMany.class))
            return field.getAnnotationOfType(ManyToMany.class).fetch();
        if (field.isAnnotatedWith(ElementCollection.class)) {
            return field.getAnnotationOfType(ElementCollection.class).fetch();
        }
        return null;
    }
}
