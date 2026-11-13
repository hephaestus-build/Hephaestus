package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Background work starts only under {@link ConditionalOnBackgroundWork}, so the {@code test}, {@code specs} and {@code
 * cds-training} profiles start none of it. Three kinds of registration start work:
 *
 * <ul>
 *   <li>a bean that holds scheduled tasks, such as a {@code ScheduledTaskRegistrar};
 *   <li>{@code @EnableScheduling}, which runs every {@code @Scheduled} method;
 *   <li>a startup callback that starts a thread, schedules on an executor or starts a registrar, directly or through
 *       the concrete methods it calls: a bean's construction or {@code @Bean} method, {@code @PostConstruct}, {@code
 *       afterPropertiesSet}, {@code afterSingletonsInstantiated}, a lifecycle's {@code start}, a runner's {@code run},
 *       and a listener for the application's start. Such a path covers a {@code LISTEN} subscription that opens its
 *       connection, and a claimer that starts its threads.
 * </ul>
 *
 * The condition goes on the bean method, or on the class that declares or is the bean. An enclosing class's condition
 * does not count: Spring reads a nested configuration on its own.
 */
class BackgroundWorkArchTest extends HephaestusArchitectureTest {

    /**
     * Startup paths that start threads for the requests a process serves, not work of its own, so they run wherever those
     * requests can. A {@code LISTEN} connection is such a thread when the requests of this process wait for what it
     * delivers: the integration tests of these requests need it. A subscriber that acts on its own, such as one that
     * posts to a provider or starts a sandbox, is background work.
     */
    private static final Map<String, String> REQUEST_WORK = Map.of(
            "de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits.start",
            "renews and fences the permits that this process's model calls hold, and wakes the calls that wait for one",
            "de.tum.cit.aet.hephaestus.agent.proxy.ScanCallLeases.<init>",
            "renews the scan call leases that this process's calls hold",
            "de.tum.cit.aet.hephaestus.mentor.MentorTimeline$PartialAnswersConfiguration.mentorPartialAnswers",
            "keeps the partial answers of running turns, which conversation reads and live streams return");

    private static final Set<String> THREAD_OWNERS = Set.of(
            "java.lang.Thread",
            "java.lang.Thread$Builder",
            "java.lang.Thread$Builder$OfPlatform",
            "java.lang.Thread$Builder$OfVirtual");

    private static final Set<String> EXECUTOR_OWNERS = Set.of(
            "java.util.concurrent.Executor",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.ScheduledExecutorService",
            "java.util.Timer",
            "org.springframework.core.task.TaskExecutor",
            "org.springframework.core.task.AsyncTaskExecutor",
            "org.springframework.scheduling.TaskScheduler");

    private static final Set<String> STARTUP_EVENTS = Set.of(
            "org.springframework.boot.context.event.ApplicationReadyEvent",
            "org.springframework.boot.context.event.ApplicationStartedEvent",
            "org.springframework.context.event.ContextRefreshedEvent");

    @Test
    void shouldScheduleTasksOnlyWithBackgroundWork() {
        assertThat(scheduledTaskBeans(classes)).isEmpty();
    }

    @Test
    void shouldRunScheduledMethodsOnlyWithBackgroundWork() {
        assertThat(schedulingSwitches(classes)).isEmpty();
    }

    @Test
    void shouldStartThreadsAtStartupOnlyWithBackgroundWork() {
        assertThat(startupThreads(classes)).isEmpty();
    }

    @Test
    void shouldExemptOnlyStartupPathsThatStillStartThreads() {
        List<String> entries = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                if (isStartup(unit) && reach(unit).isPresent()) entries.add(key(unit));
            }
        }
        assertThat(entries).containsAll(REQUEST_WORK.keySet());
    }

    // The fixtures are no @Configuration or @Component: integration contexts scan the test tree, and would register
    // them.

    @Test
    void shouldFindEveryStartThatRunsWithoutBackgroundWork() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(
                        Registrars.class,
                        GatedRegistrars.class,
                        Switch.class,
                        GatedSwitch.class,
                        LifecycleThread.class,
                        GatedLifecycleThread.class,
                        Notifications.class,
                        Subscriber.class,
                        Subscriptions.class,
                        GatedSubscriptions.class,
                        Passes.class,
                        GatedPasses.class,
                        GatedPassesBean.class);

        assertThat(scheduledTaskBeans(fixtures))
                .singleElement()
                .asString()
                .contains(Registrars.class.getName() + ".tasks");
        assertThat(schedulingSwitches(fixtures)).singleElement().asString().contains(Switch.class.getName());
        assertThat(startupThreads(fixtures))
                .hasSize(4)
                .anyMatch(found -> found.startsWith(LifecycleThread.class.getName() + ".start"))
                .anyMatch(found -> found.startsWith(Subscriptions.class.getName() + ".subscriber("))
                .anyMatch(found -> found.startsWith(Subscriptions.class.getName() + ".onReady("))
                .anyMatch(found -> found.startsWith(Passes.class.getName() + ".onApplicationEvent("))
                .noneMatch(found -> found.contains("Gated"));
    }

    private static List<String> scheduledTaskBeans(JavaClasses types) {
        return types.stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(Bean.class)
                        && method.getRawReturnType().isAssignableTo(ScheduledTaskHolder.class)
                        && !gated(method))
                .map(method -> method.getFullName() + " schedules tasks; add @ConditionalOnBackgroundWork")
                .toList();
    }

    private static List<String> schedulingSwitches(JavaClasses types) {
        return types.stream()
                .filter(type -> type.isAnnotatedWith(EnableScheduling.class) && !gated(type))
                .map(type -> type.getName() + " runs @Scheduled methods; add @ConditionalOnBackgroundWork")
                .toList();
    }

    private static List<String> startupThreads(JavaClasses types) {
        List<String> found = new ArrayList<>();
        for (JavaClass type : types) {
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                if (!isStartup(unit) || REQUEST_WORK.containsKey(key(unit)) || gated(unit, types)) continue;
                reach(unit)
                        .ifPresent(path -> found.add(unit.getFullName()
                                + " starts background work; add @ConditionalOnBackgroundWork:\n    "
                                + String.join("\n    -> ", path)));
            }
        }
        return found;
    }

    private static String key(JavaCodeUnit unit) {
        return unit.getOwner().getName() + "." + unit.getName();
    }

    private static boolean isStartup(JavaCodeUnit unit) {
        if (unit.isAnnotatedWith(Bean.class) || unit.isAnnotatedWith("jakarta.annotation.PostConstruct")) return true;
        JavaClass owner = unit.getOwner();
        if (unit.isConstructor()) return owner.isMetaAnnotatedWith("org.springframework.stereotype.Component");
        var listener = unit.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().isEquivalentTo(EventListener.class))
                .findFirst();
        if (listener.isPresent()) {
            List<JavaClass> events = new ArrayList<>(unit.getRawParameterTypes());
            for (String attribute : List.of("value", "classes")) {
                listener.get().get(attribute).ifPresent(declared -> events.addAll(List.of((JavaClass[]) declared)));
            }
            return events.stream().anyMatch(event -> STARTUP_EVENTS.contains(event.getName()));
        }
        boolean noArguments = unit.getRawParameterTypes().isEmpty();
        return switch (unit.getName()) {
            case "start" -> noArguments && owner.isAssignableTo("org.springframework.context.Lifecycle");
            case "run" ->
                owner.isAssignableTo("org.springframework.boot.ApplicationRunner")
                        || owner.isAssignableTo("org.springframework.boot.CommandLineRunner");
            case "afterPropertiesSet" ->
                noArguments && owner.isAssignableTo("org.springframework.beans.factory.InitializingBean");
            case "afterSingletonsInstantiated" ->
                owner.isAssignableTo("org.springframework.beans.factory.SmartInitializingSingleton");
            case "onApplicationEvent" ->
                owner.isAssignableTo(ApplicationListener.class)
                        && unit.getRawParameterTypes().stream()
                                .anyMatch(event -> STARTUP_EVENTS.contains(event.getName()));
            default -> false;
        };
    }

    /** The first call path from {@code entry} to a start of work on another thread, if there is one. */
    private static Optional<List<String>> reach(JavaCodeUnit entry) {
        Map<JavaCodeUnit, JavaCodeUnit> callers = new HashMap<>();
        callers.put(entry, entry);
        var pending = new ArrayDeque<JavaCodeUnit>(List.of(entry));
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                if (startsWork(call)) {
                    List<String> path = new ArrayList<>();
                    path.add(call.getTarget().getFullName() + " " + call.getSourceCodeLocation());
                    for (JavaCodeUnit at = unit; !at.equals(entry); at = Objects.requireNonNull(callers.get(at))) {
                        path.addFirst(at.getFullName());
                    }
                    return Optional.of(path);
                }
                call.getTarget().resolveMember().ifPresent(next -> {
                    if (callers.putIfAbsent(next, unit) == null) pending.add(next);
                });
            }
            for (var call : unit.getConstructorCallsFromSelf()) {
                call.getTarget().resolveMember().ifPresent(next -> {
                    if (callers.putIfAbsent(next, unit) == null) pending.add(next);
                });
            }
        }
        return Optional.empty();
    }

    private static boolean startsWork(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        if (THREAD_OWNERS.contains(owner)) return name.equals("start") || name.equals("startVirtualThread");
        if (EXECUTOR_OWNERS.contains(owner))
            return Stream.of("execute", "submit", "invokeAll", "invokeAny", "schedule")
                    .anyMatch(name::startsWith);
        return owner.equals(ScheduledTaskRegistrar.class.getName())
                && (name.equals("afterPropertiesSet") || name.equals("scheduleTasks"));
    }

    private static boolean gated(JavaMethod beanMethod) {
        return beanMethod.isAnnotatedWith(ConditionalOnBackgroundWork.class) || gated(beanMethod.getOwner());
    }

    private static boolean gated(JavaClass type) {
        return type.isAnnotatedWith(ConditionalOnBackgroundWork.class);
    }

    /** A bean method by its own condition; any other callback by the condition of its bean's class or bean methods. */
    private static boolean gated(JavaCodeUnit unit, JavaClasses types) {
        if (unit instanceof JavaMethod method && method.isAnnotatedWith(Bean.class)) return gated(method);
        JavaClass bean = unit.getOwner();
        if (gated(bean)) return true;
        if (bean.isMetaAnnotatedWith("org.springframework.stereotype.Component")) return false;
        List<JavaMethod> definitions = types.stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(Bean.class)
                        && method.getRawReturnType().isEquivalentTo(bean.reflect()))
                .toList();
        return !definitions.isEmpty() && definitions.stream().allMatch(BackgroundWorkArchTest::gated);
    }

    static class Registrars {
        @Bean
        ScheduledTaskRegistrar tasks() {
            return new ScheduledTaskRegistrar();
        }

        @Bean
        Passes passes() {
            return new Passes();
        }
    }

    @ConditionalOnBackgroundWork
    static class GatedRegistrars {
        @Bean
        ScheduledTaskRegistrar tasks() {
            return new ScheduledTaskRegistrar();
        }
    }

    @EnableScheduling
    static class Switch {}

    @EnableScheduling
    @ConditionalOnBackgroundWork
    static class GatedSwitch {}

    static class LifecycleThread implements SmartLifecycle {
        private volatile boolean running;

        @Override
        public void start() {
            running = true;
            Thread.ofPlatform().start(() -> {});
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    @ConditionalOnBackgroundWork
    static class GatedLifecycleThread extends LifecycleThread {
        @Override
        public void start() {
            Thread.ofVirtual().start(() -> {});
        }
    }

    /** Opens its {@code LISTEN} connection on a thread of its own when the first listener subscribes. */
    static class Notifications {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean listening;

        void subscribe(Runnable listener) {
            listeners.add(listener);
            if (!listening) {
                listening = true;
                Thread.ofPlatform().start(this::listen);
            }
        }

        private void listen() {
            listeners.forEach(Runnable::run);
        }
    }

    static class Subscriber {
        Subscriber(Notifications notifications) {
            notifications.subscribe(() -> {});
        }
    }

    static class Subscriptions {
        @Bean
        Subscriber subscriber(Notifications notifications) {
            return new Subscriber(notifications);
        }

        @EventListener(ApplicationReadyEvent.class)
        void onReady() {
            new Notifications().subscribe(() -> {});
        }
    }

    @ConditionalOnBackgroundWork
    static class GatedSubscriptions extends Subscriptions {}

    /** Started by the application's start, and defined by {@link Registrars#passes} without the condition. */
    static class Passes implements ApplicationListener<ApplicationReadyEvent> {
        private final ScheduledTaskRegistrar tasks = new ScheduledTaskRegistrar();

        @Override
        public void onApplicationEvent(ApplicationReadyEvent ready) {
            tasks.addFixedDelayTask(new FixedDelayTask(() -> {}, Duration.ofSeconds(1), Duration.ZERO));
            tasks.afterPropertiesSet();
        }
    }

    /** Defined only by {@link GatedPassesBean#gatedPasses}, which carries the condition. */
    static class GatedPasses implements ApplicationListener<ApplicationReadyEvent> {
        @Override
        public void onApplicationEvent(ApplicationReadyEvent ready) {
            Thread.ofVirtual().start(() -> {});
        }
    }

    static class GatedPassesBean {
        @Bean
        @ConditionalOnBackgroundWork
        GatedPasses gatedPasses() {
            return new GatedPasses();
        }
    }
}
