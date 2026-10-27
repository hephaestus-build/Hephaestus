package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.WebhookReceiverScope;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.repository.Repository;

/**
 * The webhook role scans only {@link WebhookReceiverScope}, and has no database (ADR 0066). Two rules keep both true:
 * every receiver component is in the scope, and nothing the scope admits for the webhook role reaches a persistence
 * type, directly or through other application classes. A server- or worker-gated class in a {@code webhook} package
 * is scanned but never created there.
 */
class WebhookReceiverScopeArchitectureTest extends HephaestusArchitectureTest {

    private static final List<Class<?>> RECEIVER_SPIS = List.of(
            WebhookSignatureVerifier.class,
            SubjectKeyDeriver.class,
            WebhookPublishGate.class,
            WebhookSecretSource.class);

    private static final List<String> PERSISTENCE_PACKAGES = List.of(
            "jakarta.persistence",
            "org.springframework.data",
            "org.springframework.jdbc",
            "org.springframework.orm",
            "org.springframework.transaction",
            "javax.sql",
            "liquibase",
            "org.hibernate");

    private static final String BASE = BASE_PACKAGE + ".";

    private final WebhookReceiverScope scope = new WebhookReceiverScope();
    private final MetadataReaderFactory readers = new CachingMetadataReaderFactory();
    private final Map<String, Boolean> scopeCache = new HashMap<>();

    @Test
    void shouldScanEveryReceiverComponentWhenOnlyTheWebhookRoleRuns() throws IOException {
        Set<String> excluded = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            boolean receiver = javaClass.isAnnotatedWith(ConditionalOnWebhookRole.class)
                    || (!javaClass.isInterface() && RECEIVER_SPIS.stream().anyMatch(javaClass::isAssignableTo));
            if (receiver && !inScope(javaClass)) {
                excluded.add(javaClass.getName());
            }
        }

        assertThat(excluded)
                .as("receiver components the webhook role's scan leaves out; move them or mark them @ReceiverComponent")
                .isEmpty();
    }

    @Test
    void shouldReachNoPersistenceTypeWhenTheWebhookRoleCanCreateIt() throws IOException {
        Set<String> violations = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (creatableInTheWebhookRole(javaClass)) {
                List<JavaClass> path = pathToPersistence(javaClass);
                if (!path.isEmpty()) {
                    violations.add(path.stream().map(JavaClass::getName).collect(Collectors.joining(" -> ")));
                }
            }
        }

        assertThat(violations)
                .as("the webhook role has no database (ADR 0066); read what it needs from NATS or the request")
                .isEmpty();
    }

    /**
     * The shortest path from {@code start} to a persistence type, or empty. The walk follows every dependency and the
     * type arguments of fields and parameters, so {@code ObjectProvider<SomeService>} counts as a dependency on
     * {@code SomeService}: a bean that the receiver cannot create is a dead fallback there. It continues through
     * application classes that the webhook role does not create (server-gated ones, abstract bases, interfaces); a class
     * that it creates is checked on its own.
     */
    private List<JavaClass> pathToPersistence(JavaClass start) throws IOException {
        Map<JavaClass, JavaClass> parents = new HashMap<>();
        Deque<JavaClass> queue = new ArrayDeque<>(List.of(start));
        parents.put(start, start);
        while (!queue.isEmpty()) {
            JavaClass current = queue.removeFirst();
            for (JavaClass target : dependencies(current)) {
                if (parents.containsKey(target)) {
                    continue;
                }
                parents.put(target, current);
                if (isPersistence(target)) {
                    List<JavaClass> path = new ArrayList<>();
                    for (JavaClass step = target;
                            !step.equals(start);
                            step = Objects.requireNonNull(parents.get(step))) {
                        path.addFirst(step);
                    }
                    path.addFirst(start);
                    return path;
                }
                if (target.getName().startsWith(BASE) && !creatableInTheWebhookRole(target)) {
                    queue.addLast(target);
                }
            }
        }
        return List.of();
    }

    private static Set<JavaClass> dependencies(JavaClass javaClass) {
        Set<JavaClass> targets = new HashSet<>();
        for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
            targets.add(dependency.getTargetClass().getBaseComponentType());
        }
        for (JavaField field : javaClass.getFields()) {
            addTypeArguments(field.getType(), targets);
        }
        for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
            codeUnit.getParameterTypes().forEach(type -> addTypeArguments(type, targets));
        }
        return targets;
    }

    private static void addTypeArguments(JavaType type, Set<JavaClass> targets) {
        if (type instanceof JavaParameterizedType parameterized) {
            for (JavaType argument : parameterized.getActualTypeArguments()) {
                targets.add(argument.toErasure().getBaseComponentType());
                addTypeArguments(argument, targets);
            }
        } else if (type instanceof JavaWildcardType wildcard) {
            wildcard.getUpperBounds().forEach(bound -> addTypeArguments(bound, targets));
        }
    }

    private boolean inScope(JavaClass javaClass) throws IOException {
        Boolean cached = scopeCache.get(javaClass.getName());
        if (cached == null) {
            cached = javaClass.getName().startsWith(BASE)
                    && !scope.match(readers.getMetadataReader(javaClass.getName()), readers);
            scopeCache.put(javaClass.getName(), cached);
        }
        return cached;
    }

    /** In the scope, concrete, and not gated to another role: the webhook role can create a bean of it. */
    private boolean creatableInTheWebhookRole(JavaClass javaClass) throws IOException {
        return inScope(javaClass)
                && !javaClass.isInterface()
                && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                && !gatedOffTheWebhookRole(javaClass);
    }

    private static boolean gatedOffTheWebhookRole(JavaClass javaClass) {
        JavaClass topLevel = javaClass;
        while (topLevel.getEnclosingClass().isPresent()) {
            topLevel = topLevel.getEnclosingClass().orElseThrow();
        }
        return topLevel.isAnnotatedWith(ConditionalOnServerRole.class)
                || topLevel.isAnnotatedWith(ConditionalOnWorkerRole.class);
    }

    private static boolean isPersistence(JavaClass target) {
        String name = target.getName();
        return PERSISTENCE_PACKAGES.stream().anyMatch(prefix -> name.startsWith(prefix + "."))
                || target.isAnnotatedWith(Entity.class)
                || (!target.getName().startsWith("org.springframework.data")
                        && target.isAssignableTo(Repository.class));
    }
}
