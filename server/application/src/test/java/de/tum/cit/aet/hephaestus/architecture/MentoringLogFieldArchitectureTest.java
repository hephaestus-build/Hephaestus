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
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.relationship.MentoringEventMetrics;
import de.tum.cit.aet.hephaestus.relationship.MentoringLog;
import de.tum.cit.aet.hephaestus.relationship.MentoringLogEvent;
import de.tum.cit.aet.hephaestus.relationship.MentoringLogField;
import de.tum.cit.aet.hephaestus.relationship.MentoringTrace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.Arrays;
import java.util.Formatter;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Mentoring code uses the three typed telemetry emitters. Only the emitters use the telemetry libraries.
 *
 * <p>A producer may depend only on the packages that {@link #ALLOWED_PACKAGES} names, minus the types that
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
                            "java.util.regex",
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
            Formatter.class,
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

    private static final ArchCondition<JavaClass> RAW_DEPENDENCY = new ArchCondition<>("access an unapproved type") {
        @Override
        public void check(JavaClass owner, ConditionEvents events) {
            for (var dependency : owner.getDirectDependenciesFromSelf()) {
                var target = dependency.getTargetClass().getBaseComponentType();
                if (!ALLOWED.test(target) && !emitterDependency(owner, target)) {
                    events.add(SimpleConditionEvent.satisfied(owner, dependency.getDescription()));
                }
            }
        }
    };

    private static boolean emitterDependency(JavaClass owner, JavaClass target) {
        var targetPackage = target.getPackageName();
        if (owner.isEquivalentTo(MentoringLog.class)) return targetPackage.startsWith("org.slf4j");
        if (owner.isEquivalentTo(MentoringTrace.class)) return targetPackage.equals("io.micrometer.tracing");
        return owner.isEquivalentTo(MentoringEventMetrics.class)
                && targetPackage.equals("io.micrometer.core.instrument");
    }

    private static final DescribedPredicate<JavaAccess<?>> METER_WRITE_OUTSIDE_CONSTRUCTOR =
            DescribedPredicate.describe(
                    "bind a metric outside the constructor",
                    access -> access.getOrigin() instanceof JavaMethod
                            && access.getTargetOwner().getPackageName().startsWith("io.micrometer.core.instrument")
                            && !(access.getTargetOwner().isAssignableTo(Counter.class)
                                    && access.getTarget() instanceof AccessTarget.MethodCallTarget method
                                    && method.getName().equals("increment")
                                    && method.getRawParameterTypes().isEmpty()));

    static final ArchRule TYPED_TELEMETRY_ONLY = noClasses()
            .that()
            .resideInAnyPackage(MentoringModules.PACKAGES)
            .should(RAW_DEPENDENCY)
            .orShould(ACCESS_STANDARD_STREAMS)
            .orShould()
            .accessTargetWhere(WRITE_ACCESS)
            .because("mentoring logs, spans and meters use typed emitters that enforce the field class policy");

    private static final ArchRule METERS_BOUND_AT_CONSTRUCTION = noClasses()
            .that()
            .resideInAnyPackage(MentoringModules.PACKAGES)
            .should()
            .accessTargetWhere(METER_WRITE_OUTSIDE_CONSTRUCTOR)
            .because("mentoring methods only increment counters that already have their declared labels");

    @Test
    void shouldUseTypedTelemetryEmitters() {
        TYPED_TELEMETRY_ONLY.check(classes);
        METERS_BOUND_AT_CONSTRUCTION.check(classes);
    }

    @Test
    void shouldOnlyExposeTypedEmitterInputs() {
        checkEmitterInputs(classes.get(MentoringLog.class));
        checkEmitterInputs(classes.get(MentoringTrace.class));
        checkEmitterInputs(classes.get(MentoringEventMetrics.class));
    }

    private static void checkEmitterInputs(JavaClass emitter) {
        var allowed = belongToAnyOf(
                Class.class,
                Throwable.class,
                MentoringLogEvent.class,
                MentoringLogField.class,
                MeterRegistry.class,
                Tracer.class);
        assertThat(emitter.getCodeUnits())
                .filteredOn(unit -> !unit.getModifiers().contains(JavaModifier.PRIVATE))
                .allSatisfy(unit -> assertThat(unit.getRawParameterTypes())
                        .as("Inputs of %s", unit.getFullName())
                        .allMatch(type -> allowed.test(type.getBaseComponentType())));
    }

    /** The rule finds each write that the fixture makes: a library log call, a span, a meter and the JDK accesses. */
    @Test
    void shouldReportEveryTelemetryWriteInAMentoringPackage() {
        JavaClasses fixture = classesWithTests.that(DescribedPredicate.describe(
                "the violation fixture",
                type -> type.getName()
                        .equals("de.tum.cit.aet.hephaestus.relationship.MentoringTelemetryViolationFixture")));
        assertThat(fixture).isNotEmpty();

        assertThatThrownBy(() -> TYPED_TELEMETRY_ONLY.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("calls method <org.slf4j.Logger.info(")
                .hasMessageContaining("calls method <io.opentelemetry.api.trace.Span.setAttribute(")
                .hasMessageContaining("calls method <io.micrometer.core.instrument.Counter$Builder.tag(")
                .hasMessageContaining("calls method <reactor.core.publisher.Mono.log(")
                .hasMessageContaining("calls method <java.lang.System$Logger.log(")
                .hasMessageContaining("calls method <java.lang.System$LoggerFinder.getLogger(")
                .hasMessageContaining("calls method <java.util.logging.Logger.info(")
                .hasMessageContaining("calls method <java.lang.IO.println(")
                .hasMessageContaining("calls constructor <java.util.Formatter.<init>(")
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

        assertThatThrownBy(() -> METERS_BOUND_AT_CONSTRUCTION.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("calls method <io.micrometer.core.instrument.Counter$Builder.tag(");
        assertThatThrownBy(() -> fixture.forEach(MentoringLogFieldArchitectureTest::checkEmitterInputs))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.lang.String");
    }
}
