package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.RequestBody;

class SpringCorrectnessArchitectureTest extends HephaestusArchitectureTest {

    @Test
    void shouldKeepProviderHandlersAwayFromAutocommitDatabaseAccess() {
        noClasses()
                .that()
                .areAssignableTo(IntegrationMessageHandler.class)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..", "jakarta.persistence..")
                .because(
                        "provider handlers delegate database writes to Spring transaction boundaries where inbox erasure is fenced")
                .check(classes);
    }

    @Test
    void shouldNotHandRawProviderEnvelopesToAsynchronousJobs() {
        methods()
                .that()
                .areAnnotatedWith(Async.class)
                .or()
                .areAnnotatedWith(ApplicationModuleListener.class)
                .should(new ArchCondition<JavaMethod>("accept owned domain events rather than raw inbox payloads") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean raw = method.getRawParameterTypes().stream()
                                .anyMatch(type -> type.isAssignableTo(ProviderEvent.class));
                        events.add(new SimpleConditionEvent(
                                method,
                                !raw,
                                method.getFullName()
                                        + " must own capture and erasure independently of the dispatch thread"));
                    }
                })
                .check(classes);
    }

    @Test
    void shouldRequireTransactionalMethodsToBeProxyable() {
        ArchRule rule = methods()
                .that(transactionalMethods())
                .should()
                .bePublic()
                .andShould()
                .notBeStatic()
                .andShould()
                .notBeFinal()
                .andShould(beDeclaredInANonFinalClass())
                .because(
                        "public instance methods work consistently with JDK and class-based proxies, while class-based proxies cannot advise final methods or classes");

        rule.check(classes);
    }

    @Test
    void shouldRejectTransactionalSelfInvocation() {
        ArchRule rule = methods()
                .should(notCallTransactionalMethodsOnSelf())
                .because("self-invocation bypasses Spring's transactional proxy");

        rule.check(classes);
    }

    @Test
    void shouldRunAfterCompletionListenersOutsideTheFinishedTransaction() {
        ArchRule rule = methods()
                .that()
                .areAnnotatedWith(TransactionalEventListener.class)
                .should(runOutsideTheFinishedTransaction())
                .because("after completion the finished transaction is still bound, so a REQUIRED call joins it and"
                        + " its writes never commit (TransactionSynchronization#afterCommit, ADR 0075)");

        rule.check(classes);
    }

    @Test
    void shouldUseSpringTransactionalAnnotation() {
        ArchRule methodsRule = methods()
                .should()
                .notBeAnnotatedWith(jakarta.transaction.Transactional.class)
                .because("all transaction boundaries use Spring's annotation so the same architecture rules apply");
        ArchRule classesRule = noClasses()
                .should()
                .beAnnotatedWith(jakarta.transaction.Transactional.class)
                .because("all transaction boundaries use Spring's annotation so the same architecture rules apply");

        methodsRule.check(classes);
        classesRule.check(classes);
    }

    @Test
    void shouldRejectAutowiredFields() {
        ArchRule rule = fields().should(notBeAutowired())
                .because("required collaborators must be explicit constructor dependencies");

        rule.check(classes);
    }

    @Test
    void shouldValidateApplicationRequestBodies() {
        ArchRule rule = methods()
                .should(haveValidatedRequestBodies())
                .because("bean validation is not applied to a request body unless the parameter is @Valid");

        rule.check(classes);
    }

    @Test
    void shouldRejectTransactionsDuringPostConstruct() {
        ArchRule rule = methods()
                .that()
                .areAnnotatedWith(PostConstruct.class)
                .should(notBeEffectivelyTransactional())
                .because("@PostConstruct runs before the bean's transactional proxy is available");

        rule.check(classes);
    }

    @Test
    void shouldReturnRepositoryCollectionsDirectly() {
        ArchRule rule = methods()
                .that()
                .areDeclaredInClassesThat()
                .areAssignableTo(Repository.class)
                .should(notReturnOptionalCollection())
                .because("Spring Data multi-result queries return an empty result rather than null");

        rule.check(classes);
    }

    private static ArchCondition<JavaMethod> beDeclaredInANonFinalClass() {
        return new ArchCondition<>("be declared in a non-final class") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getOwner().getModifiers().contains(JavaModifier.FINAL)) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getDescription() + " is declared in a final class"));
                }
            }
        };
    }

    private static DescribedPredicate<JavaMethod> transactionalMethods() {
        return DescribedPredicate.describe(
                "covered by a method-level or type-level @Transactional annotation",
                SpringCorrectnessArchitectureTest::isEffectivelyTransactional);
    }

    private static ArchCondition<JavaField> notBeAutowired() {
        return new ArchCondition<>("not be annotated or meta-annotated with @Autowired") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (field.isAnnotatedWith(Autowired.class) || field.isMetaAnnotatedWith(Autowired.class)) {
                    events.add(SimpleConditionEvent.violated(field, field.getDescription() + " is field-injected"));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notCallTransactionalMethodsOnSelf() {
        return new ArchCondition<>("not call a transactional method on the same class") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getOwner().isInterface()) {
                    return;
                }
                method.getMethodCallsFromSelf().stream()
                        .filter(call -> method.getOwner()
                                .getAllClassesSelfIsAssignableTo()
                                .contains(call.getTargetOwner()))
                        .filter(call -> call.getTarget()
                                .resolveMember()
                                .map(SpringCorrectnessArchitectureTest::isTransactionallyAnnotatedMethod)
                                .orElse(false))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(
                                method,
                                method.getDescription() + " calls transactional "
                                        + call.getTarget().getDescription())));
            }
        };
    }

    private static ArchCondition<JavaMethod> haveValidatedRequestBodies() {
        return new ArchCondition<>("have @Valid on every @RequestBody parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotatedWith(RequestBody.class))
                        .filter(parameter -> parameter.getType().getAllInvolvedRawTypes().stream()
                                .anyMatch(type -> type.getPackageName().startsWith(BASE_PACKAGE)))
                        .filter(parameter -> !parameter.isAnnotatedWith(Valid.class))
                        .forEach(parameter -> events.add(SimpleConditionEvent.violated(
                                method, parameter.getDescription() + " is @RequestBody but is not @Valid")));
            }
        };
    }

    private static ArchCondition<JavaMethod> runOutsideTheFinishedTransaction() {
        return new ArchCondition<>("run on another thread or in a transaction of their own") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                // A BEFORE_COMMIT listener runs inside the live transaction and joins it on purpose.
                if (method.getAnnotationOfType(TransactionalEventListener.class).phase()
                        == TransactionPhase.BEFORE_COMMIT) {
                    return;
                }
                boolean ownBoundary = method.tryGetAnnotationOfType(Transactional.class)
                        .map(transactional -> transactional.propagation() == Propagation.REQUIRES_NEW
                                || transactional.propagation() == Propagation.NOT_SUPPORTED)
                        .orElse(false);
                if (!method.isAnnotatedWith(Async.class) && !ownBoundary) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getDescription()
                                    + " runs after completion without @Async or"
                                    + " @Transactional(REQUIRES_NEW | NOT_SUPPORTED)"));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notBeEffectivelyTransactional() {
        return new ArchCondition<>("not be transactional") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (isEffectivelyTransactional(method)) {
                    events.add(SimpleConditionEvent.violated(method, method.getDescription() + " is transactional"));
                }
            }
        };
    }

    private static boolean isEffectivelyTransactional(JavaMethod method) {
        boolean methodAnnotated = isTransactionallyAnnotatedMethod(method);
        boolean ownerAnnotated = method.getOwner().getAllClassesSelfIsAssignableTo().stream()
                .anyMatch(owner ->
                        owner.isAnnotatedWith(Transactional.class) || owner.isMetaAnnotatedWith(Transactional.class));
        boolean proxyCandidate = !method.getModifiers().contains(JavaModifier.PRIVATE)
                && !method.getModifiers().contains(JavaModifier.STATIC);
        return methodAnnotated || (ownerAnnotated && proxyCandidate);
    }

    private static boolean isTransactionallyAnnotatedMethod(JavaMethod method) {
        return method.getOwner().getAllMethods().stream()
                .filter(candidate -> candidate.getName().equals(method.getName()))
                .filter(candidate -> candidate.getRawParameterTypes().equals(method.getRawParameterTypes()))
                .anyMatch(candidate -> candidate.isAnnotatedWith(Transactional.class)
                        || candidate.isMetaAnnotatedWith(Transactional.class));
    }

    private static ArchCondition<JavaMethod> notReturnOptionalCollection() {
        return new ArchCondition<>("not return Optional-wrapped multi-result types") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                JavaType returnType = method.getReturnType();
                if (returnType instanceof JavaParameterizedType parameterized
                        && returnType.toErasure().isEquivalentTo(Optional.class)
                        && parameterized.getActualTypeArguments().stream()
                                .anyMatch(SpringCorrectnessArchitectureTest::isMultiResultType)) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getDescription() + " returns Optional-wrapped collection " + returnType.getName()));
                }
            }
        };
    }

    private static boolean isMultiResultType(JavaType type) {
        return type.toErasure().isAssignableTo(Iterable.class);
    }
}
