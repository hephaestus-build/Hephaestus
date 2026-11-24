package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaCodeUnitReference;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The mentoring modules read the time from the injected {@link Clock} (ADR 0138), so a test controls every due time,
 * deadline and expiry. A call or a method reference that reads the system clock directly is refused.
 */
class SystemClockArchTest extends HephaestusArchitectureTest {
    @Test
    void shouldReadTheTimeOnlyFromTheInjectedClock() {
        assertThat(systemClockReads(classes.that(DescribedPredicate.describe(
                        "mentoring classes", type -> MentoringModules.holds(type.getPackageName())))))
                .isEmpty();
    }

    @Test
    void shouldRefuseACallOrAMethodReferenceThatReadsTheSystemClock() {
        var fixtures = classesWithTests.that(DescribedPredicate.describe(
                "clock fixtures", type -> type.getName().startsWith(SystemClockArchTest.class.getName() + "$")));
        String reader = ReadsTheSystemClock.class.getName();
        assertThat(systemClockReads(fixtures))
                .containsExactlyInAnyOrder(
                        reader + " -> java.time.Instant.now()",
                        reader + " -> java.time.Instant.now() by reference",
                        reader + " -> java.time.OffsetDateTime.now(java.time.ZoneId)",
                        reader + " -> java.time.YearMonth.now()");
    }

    /**
     * Each access that reads the system clock: a {@code now} of any {@code java.time} type that takes no {@link Clock},
     * a system {@code Clock}, and {@code System.currentTimeMillis()}.
     */
    private static List<String> systemClockReads(JavaClasses scope) {
        var reads = new TreeSet<String>();
        for (JavaClass type : scope) {
            for (JavaCodeUnitAccess<?> access : type.getCodeUnitAccessesFromSelf()) {
                String owner = access.getTargetOwner().getName();
                String name = access.getName();
                List<JavaClass> parameters = access.getTarget().getRawParameterTypes();
                boolean noClock = parameters.stream().noneMatch(parameter -> parameter.isEquivalentTo(Clock.class));
                if ((owner.startsWith("java.time.") && name.equals("now") && noClock)
                        || (owner.equals(Clock.class.getName()) && name.startsWith("system"))
                        || (owner.equals(System.class.getName()) && name.equals("currentTimeMillis"))) {
                    reads.add(type.getName() + " -> " + owner + "." + name + "("
                            + String.join(
                                    ",",
                                    parameters.stream().map(JavaClass::getName).toList()) + ")"
                            + (access instanceof JavaCodeUnitReference<?> ? " by reference" : ""));
                }
            }
        }
        return List.copyOf(reads);
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsTheSystemClock {
        Instant now() {
            return Instant.now();
        }

        Supplier<Instant> supplier() {
            return Instant::now;
        }

        OffsetDateTime inUtc() {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }

        @SuppressWarnings("JavaTimeDefaultTimeZone") // The fixture reads the system clock on purpose.
        YearMonth month() {
            return YearMonth.now();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsTheInjectedClock {
        Instant now(Clock clock) {
            return Instant.now(clock);
        }

        YearMonth month(Clock clock) {
            return YearMonth.now(clock);
        }
    }
}
