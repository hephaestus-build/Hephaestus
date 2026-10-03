package de.tum.cit.aet.hephaestus.architecture.conditions;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reusable ArchUnit conditions for Hephaestus architecture tests.
 *
 * <p>These conditions encapsulate common patterns to avoid code duplication
 * across architecture test files. All conditions are designed to be composable
 * and provide clear, actionable violation messages.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.*;
 *
 * ArchRule rule = classes()
 *     .that().haveSimpleNameEndingWith("Service")
 *     .should(haveAtMostConstructorParameters(12))
 *     .because("Services with many dependencies violate SRP");
 * }</pre>
 *
 * @see com.tngtech.archunit.lang.ArchCondition
 */
public final class HephaestusConditions {

    private HephaestusConditions() {
        // Utility class - prevent instantiation
    }

    // CLASS CONDITIONS - Complexity & Structure

    /**
     * Condition that checks if a class has at most the specified number of
     * constructor parameters (dependency count proxy).
     *
     * <p>High constructor parameter counts indicate a class with too many
     * responsibilities (SRP violation) or poor decomposition.
     *
     * @param maxParams maximum allowed constructor parameters
     * @return condition that validates constructor parameter count
     */
    public static ArchCondition<JavaClass> haveAtMostConstructorParameters(int maxParams) {
        return new ArchCondition<>("have at most " + maxParams + " constructor parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                long maxFound = javaClass.getConstructors().stream()
                        .mapToLong(c -> c.getRawParameterTypes().size())
                        .max()
                        .orElse(0);

                if (maxFound > maxParams) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has %d constructor params (max %d) - consider splitting responsibilities",
                                    javaClass.getSimpleName(), maxFound, maxParams)));
                }
            }
        };
    }

    /**
     * Condition that checks if a class has at most the specified number of
     * constructor parameters, with support for explicit exceptions.
     *
     * @param maxParams maximum allowed constructor parameters
     * @param exceptions set of class simple names to exclude from check
     * @return condition that validates constructor parameter count
     */
    public static ArchCondition<JavaClass> haveAtMostConstructorParameters(int maxParams, Set<String> exceptions) {
        return new ArchCondition<>("have at most " + maxParams + " constructor parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (exceptions.contains(javaClass.getSimpleName())) {
                    return;
                }

                long maxFound = javaClass.getConstructors().stream()
                        .mapToLong(c -> c.getRawParameterTypes().size())
                        .max()
                        .orElse(0);

                if (maxFound > maxParams) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has %d constructor params (max %d) - consider splitting responsibilities",
                                    javaClass.getSimpleName(), maxFound, maxParams)));
                }
            }
        };
    }

    /**
     * Condition that checks if a class has at most the specified number of
     * declared business methods (excluding getters/setters/equals/hashCode/toString).
     *
     * <p>Classes with many methods often have multiple responsibilities.
     *
     * @param maxMethods maximum allowed business methods
     * @return condition that validates method count
     */
    public static ArchCondition<JavaClass> haveAtMostBusinessMethods(int maxMethods) {
        return new ArchCondition<>("have at most " + maxMethods + " business methods") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                long methodCount = javaClass.getMethods().stream()
                        .filter(m -> m.getOwner().equals(javaClass))
                        .filter(m -> !m.getName().startsWith("$"))
                        .filter(m -> !m.getName().startsWith("lambda$"))
                        .filter(m -> !m.getName().equals("equals"))
                        .filter(m -> !m.getName().equals("hashCode"))
                        .filter(m -> !m.getName().equals("toString"))
                        .filter(m -> !m.getName().startsWith("get"))
                        .filter(m -> !m.getName().startsWith("set"))
                        .filter(m -> !m.getName().startsWith("is"))
                        .filter(m -> !m.getName().equals("<init>"))
                        .filter(m -> !m.getName().equals("<clinit>"))
                        .count();

                if (methodCount > maxMethods) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has %d business methods (max %d) - consider splitting",
                                    javaClass.getSimpleName(), methodCount, maxMethods)));
                }
            }
        };
    }

    /**
     * Condition that checks if a class is final or has only private constructors
     * (utility class pattern).
     *
     * @return condition that validates utility class structure
     */
    public static ArchCondition<JavaClass> beFinalOrHaveOnlyPrivateConstructors() {
        return new ArchCondition<>("be final or have only private constructors") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean isFinal = javaClass.getModifiers().contains(JavaModifier.FINAL);
                boolean hasOnlyPrivateCtors = javaClass.getConstructors().stream()
                        .allMatch(c -> c.getModifiers().contains(JavaModifier.PRIVATE));

                if (!isFinal && !hasOnlyPrivateCtors) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s should be final or have private constructor", javaClass.getSimpleName())));
                }
            }
        };
    }

    /**
     * Condition that checks if a class is a record or has only final fields
     * (immutability pattern).
     *
     * @return condition that validates immutability
     */
    public static ArchCondition<JavaClass> beImmutable() {
        return new ArchCondition<>("be a record or have only final fields") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.isRecord()) {
                    return;
                }

                boolean hasNonFinalField = javaClass.getFields().stream()
                        .filter(f -> !f.getModifiers().contains(JavaModifier.STATIC))
                        .anyMatch(f -> !f.getModifiers().contains(JavaModifier.FINAL));

                if (hasNonFinalField) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has mutable fields - use record or final fields", javaClass.getSimpleName())));
                }
            }
        };
    }

    /**
     * Condition that checks if a class has limited dependencies via field injection
     * and constructor injection combined.
     *
     * @param maxDependencies maximum allowed dependencies
     * @return condition that validates total dependency count
     */
    public static ArchCondition<JavaClass> haveAtMostDependencies(int maxDependencies) {
        return new ArchCondition<>("have at most " + maxDependencies + " dependencies") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                // Count @Autowired fields
                long fieldInjections = javaClass.getFields().stream()
                        .filter(f -> f.isAnnotatedWith(Autowired.class))
                        .count();

                // Count constructor parameters
                long constructorParams = javaClass.getConstructors().stream()
                        .mapToLong(c -> c.getRawParameterTypes().size())
                        .max()
                        .orElse(0);

                long totalDeps = Math.max(fieldInjections, constructorParams);

                if (totalDeps > maxDependencies) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has %d dependencies (max %d) - SRP violation",
                                    javaClass.getSimpleName(), totalDeps, maxDependencies)));
                }
            }
        };
    }

    // CLASS CONDITIONS - Interface Segregation

    /**
     * Condition that checks if an interface has at most the specified number of
     * abstract methods (ISP compliance).
     *
     * @param maxMethods maximum allowed abstract methods
     * @return condition that validates interface size
     */
    public static ArchCondition<JavaClass> haveAtMostAbstractMethods(int maxMethods) {
        return new ArchCondition<>("have at most " + maxMethods + " abstract methods") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                int methodCount = (int) javaClass.getMethods().stream()
                        .filter(m -> !m.getModifiers().contains(JavaModifier.STATIC))
                        .filter(m -> m.getModifiers().contains(JavaModifier.ABSTRACT))
                        .count();

                if (methodCount > maxMethods) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has %d abstract methods (max %d) - ISP violation",
                                    javaClass.getSimpleName(), methodCount, maxMethods)));
                }
            }
        };
    }

    // METHOD CONDITIONS - Parameters & Security

    /**
     * Condition that checks if a method has at most the specified number of parameters.
     *
     * @param maxParams maximum allowed parameters
     * @return condition that validates parameter count
     */
    public static ArchCondition<JavaMethod> haveAtMostParameters(int maxParams) {
        return new ArchCondition<>("have at most " + maxParams + " parameters") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                int paramCount = method.getRawParameterTypes().size();
                if (paramCount > maxParams) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format(
                                    "%s.%s has %d parameters (max %d) - consider parameter object",
                                    method.getOwner().getSimpleName(), method.getName(), paramCount, maxParams)));
                }
            }
        };
    }

    /**
     * Condition that checks if a method has security annotation (at method or class level).
     *
     * <p>Checks for:
     * <ul>
     *   <li>@PreAuthorize on method or class</li>
     *   <li>Custom annotations containing "Require" or "Ensure"</li>
     * </ul>
     *
     * @return condition that validates security annotation presence
     */
    public static ArchCondition<JavaMethod> haveSecurityAnnotation() {
        return new ArchCondition<>("have security annotation") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean hasSecurityAnnotation = method.isAnnotatedWith(
                                "org.springframework.security.access.prepost.PreAuthorize")
                        || method.getOwner().isAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
                        || method.getAnnotations().stream()
                                .anyMatch(a -> a.getRawType().getName().contains("Require")
                                        || a.getRawType().getName().contains("Ensure"))
                        || method.getOwner().getAnnotations().stream()
                                .anyMatch(a -> a.getRawType().getName().contains("Require")
                                        || a.getRawType().getName().contains("Ensure"));

                if (!hasSecurityAnnotation) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format(
                                    "%s.%s has no security annotation",
                                    method.getOwner().getSimpleName(), method.getName())));
                }
            }
        };
    }

    /**
     * Condition that checks if endpoint methods have security annotations.
     *
     * <p>Only checks methods that have HTTP mapping annotations (@GetMapping, etc.).
     *
     * @return condition that validates security on endpoints
     */
    public static ArchCondition<JavaMethod> haveSecurityAnnotationIfEndpoint() {
        return new ArchCondition<>("have security annotation if HTTP endpoint") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!isHttpEndpoint(method)) {
                    return;
                }

                boolean hasSecurityAnnotation = method.isAnnotatedWith(
                                "org.springframework.security.access.prepost.PreAuthorize")
                        || method.getOwner().isAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
                        || method.getAnnotations().stream()
                                .anyMatch(a -> a.getRawType().getName().contains("Require")
                                        || a.getRawType().getName().contains("Ensure"))
                        || method.getOwner().getAnnotations().stream()
                                .anyMatch(a -> a.getRawType().getName().contains("Require")
                                        || a.getRawType().getName().contains("Ensure"));

                if (!hasSecurityAnnotation) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format(
                                    "%s.%s is an endpoint with no security annotation",
                                    method.getOwner().getSimpleName(), method.getName())));
                }
            }
        };
    }

    /**
     * Condition that checks if methods avoid excessive boolean parameters.
     *
     * <p>Multiple boolean parameters indicate complex APIs that are hard to use
     * correctly. Consider using enums, builder patterns, or parameter objects.
     *
     * @param maxBooleans maximum allowed boolean parameters
     * @return condition that validates boolean parameter count
     */
    public static ArchCondition<JavaMethod> haveAtMostBooleanParameters(int maxBooleans) {
        return new ArchCondition<>("have at most " + maxBooleans + " boolean parameters") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                long booleanCount = method.getRawParameterTypes().stream()
                        .filter(p ->
                                p.getName().equals("boolean") || p.getName().equals("java.lang.Boolean"))
                        .count();

                if (booleanCount > maxBooleans) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format(
                                    "%s.%s has %d boolean params - consider using enum or builder",
                                    method.getOwner().getSimpleName(), method.getName(), booleanCount)));
                }
            }
        };
    }

    /**
     * Condition that checks if methods do not declare generic Exception.
     *
     * <p>LSP principle: methods should declare specific exceptions.
     *
     * @return condition that validates exception declarations
     */
    public static ArchCondition<JavaMethod> notDeclareGenericException() {
        return new ArchCondition<>("not declare generic Exception") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean declaresGenericException = method.getThrowsClause().stream()
                        .anyMatch(t -> t.getRawType().getName().equals("java.lang.Exception"));

                if (declaresGenericException) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format(
                                    "LSP: %s.%s declares generic Exception - use specific exceptions",
                                    method.getOwner().getSimpleName(), method.getName())));
                }
            }
        };
    }

    /**
     * Condition that checks if a field's owner has a specific annotation.
     *
     * @param annotationName fully qualified annotation name
     * @return condition that validates containing class annotation
     */
    public static ArchCondition<JavaField> beInClassAnnotatedWith(String annotationName) {
        return new ArchCondition<>("be in class annotated with " + annotationName) {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (!field.getOwner().isAnnotatedWith(annotationName)) {
                    events.add(SimpleConditionEvent.violated(
                            field,
                            String.format(
                                    "%s.%s is not in class with %s",
                                    field.getOwner().getSimpleName(), field.getName(), annotationName)));
                }
            }
        };
    }

    /**
     * Condition that validates Workspace fields have non-null constraints.
     *
     * @return condition that validates workspace field nullability
     */
    public static ArchCondition<JavaField> beNotNullableIfWorkspaceType() {
        return new ArchCondition<>("be not nullable if Workspace type") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (!field.getRawType().getSimpleName().equals("Workspace")) {
                    return;
                }

                boolean hasNotNullAnnotation = field.isAnnotatedWith(NotNull.class)
                        || field.isAnnotatedWith("org.jetbrains.annotations.NotNull");

                boolean hasNonNullableJoinColumn = field.getAnnotations().stream()
                        .filter(a -> a.getRawType().getSimpleName().equals("JoinColumn"))
                        .findFirst()
                        .map(a -> {
                            try {
                                Object nullable = a.getExplicitlyDeclaredProperty("nullable");
                                return Boolean.FALSE.equals(nullable);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .orElse(false);

                boolean hasRequiredManyToOne = field.getAnnotations().stream()
                        .filter(a -> a.getRawType().getSimpleName().equals("ManyToOne"))
                        .findFirst()
                        .map(a -> {
                            try {
                                Object optional = a.getExplicitlyDeclaredProperty("optional");
                                return Boolean.FALSE.equals(optional);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .orElse(false);

                if (!hasNotNullAnnotation && !hasNonNullableJoinColumn && !hasRequiredManyToOne) {
                    events.add(SimpleConditionEvent.violated(
                            field,
                            String.format(
                                    "NULLABLE WORKSPACE: %s.%s should have @NotNull or @JoinColumn(nullable=false)",
                                    field.getOwner().getSimpleName(), field.getName())));
                }
            }
        };
    }

    // HELPER METHODS - Predicates

    /**
     * Checks if a method is an HTTP endpoint (has @GetMapping, @PostMapping, etc.).
     *
     * @param method the method to check
     * @return true if the method has an HTTP mapping annotation
     */
    public static boolean isHttpEndpoint(JavaMethod method) {
        return (method.isAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping"));
    }

    /**
     * Checks if a method is a synthetic or infrastructure method.
     *
     * @param method the method to check
     * @return true if the method is synthetic (lambda, bridge, etc.)
     */
    public static boolean isSyntheticMethod(JavaMethod method) {
        String name = method.getName();
        return name.startsWith("$") || name.startsWith("lambda$") || name.equals("<init>") || name.equals("<clinit>");
    }

    /**
     * Checks if a method is a standard Object method or accessor.
     *
     * @param method the method to check
     * @return true if equals/hashCode/toString/getter/setter
     */
    public static boolean isBoilerplateMethod(JavaMethod method) {
        String name = method.getName();
        return (name.equals("equals")
                || name.equals("hashCode")
                || name.equals("toString")
                || name.startsWith("get")
                || name.startsWith("set")
                || name.startsWith("is"));
    }

    /**
     * Creates a predicate that filters to business methods only.
     *
     * @return predicate that matches business methods
     */
    public static Predicate<JavaMethod> isBusinessMethod() {
        return method -> !isSyntheticMethod(method) && !isBoilerplateMethod(method);
    }

    /**
     * Condition that checks if a class has workspace-related dependencies.
     *
     * <p>Useful for validating scheduled jobs and async handlers that must
     * iterate workspaces or receive workspace context.
     *
     * @param workspaceDependencyNames names of types that provide workspace access
     * @return condition that validates workspace dependency presence
     */
    public static ArchCondition<JavaClass> haveWorkspaceAwareDependencies(Set<String> workspaceDependencyNames) {
        return new ArchCondition<>("have workspace-aware dependencies") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<String> dependencies = javaClass.getFields().stream()
                        .map(f -> f.getRawType().getSimpleName())
                        .collect(Collectors.toSet());

                javaClass
                        .getConstructors()
                        .forEach(c -> c.getRawParameterTypes().forEach(p -> dependencies.add(p.getSimpleName())));

                boolean hasWorkspaceAwareDep = dependencies.stream().anyMatch(workspaceDependencyNames::contains);

                if (!hasWorkspaceAwareDep) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            String.format(
                                    "%s has no workspace-aware dependencies (%s)",
                                    javaClass.getSimpleName(), workspaceDependencyNames)));
                }
            }
        };
    }

    /**
     * Condition that checks if a method has workspace context parameter.
     *
     * @return condition that validates workspace context in method signature
     */
    public static ArchCondition<JavaMethod> haveWorkspaceContextParameter() {
        return new ArchCondition<>("have WorkspaceContext parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean hasWorkspaceContext = method.getRawParameterTypes().stream()
                        .anyMatch(p -> p.getSimpleName().equals("WorkspaceContext"));

                boolean hasWorkspaceSecurityAnnotation = method.getAnnotations().stream()
                                .anyMatch(a -> a.getRawType().getSimpleName().contains("Workspace")
                                        || a.getRawType().getSimpleName().contains("Ensure")
                                        || a.getRawType().getSimpleName().contains("Require"))
                        || method.getOwner().getAnnotations().stream()
                                .anyMatch(a -> a.getRawType().getSimpleName().contains("Workspace")
                                        || a.getRawType().getSimpleName().contains("Ensure"));

                if (!hasWorkspaceContext && !hasWorkspaceSecurityAnnotation) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format(
                                    "%s.%s has no WorkspaceContext or workspace security annotation",
                                    method.getOwner().getSimpleName(), method.getName())));
                }
            }
        };
    }
}
