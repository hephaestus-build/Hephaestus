package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mentoring modules read the time from the injected {@link Clock} (ADR 0138), so a test controls every due time,
 * deadline and expiry. A call that reads the system clock directly is refused.
 */
class SystemClockArchTest extends HephaestusArchitectureTest {
    private static final Set<String> TIME_TYPES = Set.of(
            Instant.class.getName(),
            LocalDate.class.getName(),
            LocalDateTime.class.getName(),
            LocalTime.class.getName(),
            OffsetDateTime.class.getName(),
            ZonedDateTime.class.getName());

    private static final DescribedPredicate<JavaMethodCall> READS_THE_SYSTEM_CLOCK =
            DescribedPredicate.describe("read the system clock", call -> {
                String owner = call.getTargetOwner().getName();
                String name = call.getName();
                // now() and now(ZoneId) read the system clock. Only now(Clock) reads the injected one.
                boolean noClock = call.getTarget().getRawParameterTypes().stream()
                        .noneMatch(parameter -> parameter.isEquivalentTo(Clock.class));
                return (TIME_TYPES.contains(owner) && name.equals("now") && noClock)
                        || (owner.equals(Clock.class.getName()) && name.startsWith("system"))
                        || (owner.equals(System.class.getName()) && name.equals("currentTimeMillis"));
            });

    private static final ArchRule INJECTED_CLOCK_ONLY = noClasses()
            .that()
            .resideInAnyPackage(MentoringModules.PACKAGES)
            .should()
            .callMethodWhere(READS_THE_SYSTEM_CLOCK)
            .because("a test moves the time through the injected Clock, and Instant.now(clock) reads it")
            .allowEmptyShould(true);

    @Test
    void shouldReadTheTimeOnlyFromTheInjectedClock() {
        INJECTED_CLOCK_ONLY.check(classes);
    }

    @Test
    void shouldRefuseACallThatReadsTheSystemClock() {
        var fixtures = classesWithTests.that(DescribedPredicate.describe(
                "clock fixtures", type -> type.getName().startsWith(SystemClockArchTest.class.getName() + "$")));
        assertThat(noClasses()
                        .should()
                        .callMethodWhere(READS_THE_SYSTEM_CLOCK)
                        .evaluate(fixtures)
                        .getFailureReport()
                        .getDetails())
                .hasSize(2)
                .allSatisfy(detail -> assertThat(detail).contains("ReadsTheSystemClock"))
                .anySatisfy(detail -> assertThat(detail).contains("Instant.now()"))
                .anySatisfy(detail -> assertThat(detail).contains("OffsetDateTime.now(java.time.ZoneId)"));
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsTheSystemClock {
        Instant now() {
            return Instant.now();
        }

        OffsetDateTime inUtc() {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsTheInjectedClock {
        Instant now(Clock clock) {
            return Instant.now(clock);
        }
    }
}
