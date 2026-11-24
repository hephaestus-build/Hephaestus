package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.relationship.MentoringLog;
import de.tum.cit.aet.hephaestus.relationship.MentoringLogKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A mentoring log record, trace and metric carries only allowed field classes (ADR 0125; proposed ADR 0138 lists this
 * test under Confirmation). A mentoring class writes a record only through {@link MentoringLog}: its event is a constant
 * of a closed enum with literal text, and each field has a key from a closed list whose value class is fixed.
 *
 * <p>The rule checks the direct dependencies and accesses of each mentoring production class except {@link
 * MentoringLog}. A class may depend only on the packages that {@link #ALLOWED_PACKAGES} names, minus the types that
 * {@link #JDK_WRITE_POINTS} refuses. It may not access {@code System.out} or {@code System.err}, or make an access that
 * {@link #WRITE_ACCESS} names. A new package changes the list in review, after a reviewer checks it for a method that writes
 * its argument to a log, a console, a meter or a span. The rule catches an ordinary log call; it does not stop a
 * deliberate detour through method handles, and it cannot see the message of an exception that the JDK prints.
 */
class MentoringLogFieldArchitectureTest extends HephaestusArchitectureTest {

    /**
     * The packages that a mentoring class may depend on. A package without a trailing {@code ..} excludes its
     * subpackages, so {@code java.util} excludes {@code java.util.logging}. The mentoring modules depend on no other
     * Hephaestus module (ADR 0138).
     */
    private static final String[] ALLOWED_PACKAGES = Stream.concat(
                    Arrays.stream(MentoringModules.PACKAGES),
                    Stream.of(
                            "java.lang",
                            "java.lang.annotation",
                            "java.lang.invoke",
                            "java.lang.runtime",
                            "java.math",
                            "java.time..",
                            "java.util",
                            "java.util.function",
                            "java.util.stream",
                            "org.jspecify.annotations",
                            "org.springframework.modulith"))
            .toArray(String[]::new);

    /**
     * The types of an allowed package that write a caller's value to a log or a console, that start a process whose
     * arguments the JDK can log, or that name a thread, whose name the JDK prints with an uncaught exception. A
     * mentoring class needs none of them.
     */
    private static final DescribedPredicate<JavaClass> JDK_WRITE_POINTS = belongToAnyOf(
            System.Logger.class,
            System.LoggerFinder.class,
            IO.class,
            ProcessBuilder.class,
            Runtime.class,
            ThreadGroup.class,
            ResourceBundle.class,
            Thread.Builder.class,
            Thread.UncaughtExceptionHandler.class,
            Timer.class);

    /**
     * A call to, or a reference to, a method that prints a throwable, or that creates or renames a thread. ArchUnit's
     * stream condition sees only a direct call to {@code printStackTrace}, not a method reference. A mentoring class
     * may still read the current thread.
     */
    private static final DescribedPredicate<JavaAccess<?>> WRITE_ACCESS = DescribedPredicate.describe(
            "a method that prints a throwable or names a thread",
            access -> access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                    && ((target.getName().equals("printStackTrace")
                                    && target.getOwner().isAssignableTo(Throwable.class))
                            || (target.getOwner().isAssignableTo(Thread.class)
                                    && (target.getName().equals("<init>")
                                            || target.getName().equals("setName")))));

    private static final DescribedPredicate<JavaClass> IN_ALLOWED_PACKAGE = resideInAnyPackage(ALLOWED_PACKAGES);

    private static final DescribedPredicate<JavaClass> ALLOWED =
            DescribedPredicate.describe("an allowed package", (JavaClass type) -> {
                JavaClass base = type.getBaseComponentType();
                return base.isPrimitive() || (IN_ALLOWED_PACKAGE.test(base) && !JDK_WRITE_POINTS.test(base));
            });

    /**
     * The keys that a mentoring record may carry, by their written name. A new key changes this list in review: it
     * must name a row, a count, a duration, a flag or a code, and never a person.
     */
    private static final Map<String, Class<?>> ALLOWED_KEYS = Map.of(
            "workspace.id", Long.class,
            "run.id", UUID.class,
            "run.epoch", Integer.class,
            "mentoring.duration_ms", Duration.class);

    static final ArchRule TELEMETRY_ONLY_THROUGH_THE_MENTORING_LOGGER = noClasses()
            .that()
            .resideInAnyPackage(MentoringModules.PACKAGES)
            .and()
            .doNotBelongToAnyOf(MentoringLog.class)
            .should()
            .dependOnClassesThat(DescribedPredicate.not(ALLOWED))
            .orShould(ACCESS_STANDARD_STREAMS)
            .orShould()
            .accessTargetWhere(WRITE_ACCESS)
            .because("a mentoring record, trace or metric carries only allowed field classes: log through"
                    + " MentoringLog, and allow a package only after checking it for log writes");

    @Test
    void shouldDependOnlyOnPackagesWithoutALogWrite() {
        TELEMETRY_ONLY_THROUGH_THE_MENTORING_LOGGER.check(classes);
    }

    /** The rule finds each write that the fixture makes: a library log call, a span, a meter and the JDK accesses. */
    @Test
    void shouldReportEveryTelemetryWriteInAMentoringPackage() {
        JavaClasses fixture = classesWithTests.that(DescribedPredicate.describe(
                "the violation fixture",
                type -> type.getName()
                        .equals("de.tum.cit.aet.hephaestus.relationship.MentoringTelemetryViolationFixture")));
        assertThat(fixture).isNotEmpty();

        assertThatThrownBy(() -> TELEMETRY_ONLY_THROUGH_THE_MENTORING_LOGGER.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("calls method <org.slf4j.Logger.info(")
                .hasMessageContaining("calls method <io.opentelemetry.api.trace.Span.setAttribute(")
                .hasMessageContaining("calls method <io.micrometer.core.instrument.Counter$Builder.tag(")
                .hasMessageContaining("calls method <reactor.core.publisher.Mono.log(")
                .hasMessageContaining("calls method <java.lang.System$Logger.log(")
                .hasMessageContaining("calls method <java.lang.System$LoggerFinder.getLogger(")
                .hasMessageContaining("calls method <java.util.logging.Logger.info(")
                .hasMessageContaining("calls method <java.lang.IO.println(")
                .hasMessageContaining("references method <java.lang.Throwable.printStackTrace()>")
                .hasMessageContaining("calls method <java.lang.Thread$UncaughtExceptionHandler.uncaughtException(")
                .hasMessageContaining("gets field <java.lang.System.err>")
                .hasMessageContaining("calls constructor <java.lang.Thread.<init>(")
                .hasMessageContaining("calls method <java.lang.Thread.setName(")
                .hasMessageContaining("calls method <java.lang.Thread$Builder$OfVirtual.name(")
                .hasMessageContaining("calls constructor <java.util.Timer.<init>(")
                .hasMessageContaining("calls constructor <java.lang.ProcessBuilder.<init>(")
                .hasMessageContaining("calls method <java.lang.Runtime.getRuntime()>")
                .hasMessageContaining("calls constructor <java.lang.ThreadGroup.<init>(")
                .hasMessageContaining("calls method <java.util.ResourceBundle.getBundle(");
    }

    @Test
    void shouldCarryOnlyTheAllowedKeysWithTheirValueClasses() {
        assertThat(Arrays.stream(MentoringLogKey.values())
                        .collect(Collectors.toMap(MentoringLogKey::key, MentoringLogKey::type)))
                .as("the written keys of a mentoring record and their value classes")
                .containsExactlyInAnyOrderEntriesOf(ALLOWED_KEYS);
    }
}
