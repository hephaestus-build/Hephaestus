package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.Repository;
import org.springframework.orm.ObjectRetrievalFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Calls every repository method that the application declares through the real statement inspector, on a thread with
 * no bypass open. A refused method is a statement that fails at runtime, often inside a catch that logs it, on a path
 * no test may reach. The fix is to bind the workspace, or to put {@code @WorkspaceAgnostic} on the method itself: a
 * caller's marker does not follow work onto an executor.
 *
 * <p>Each call runs in its own transaction that rolls back. The arguments are placeholders, so a default method that
 * stops early leaves its later statements unchecked.
 */
class RepositoryMethodsTenancyIntegrationTest extends BaseIntegrationTest {

    private static final String APPLICATION = "de.tum.cit.aet.hephaestus";

    private static final class Unbuildable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unbuildable(String type) {
            super(type);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private EntityManager entities;

    @Test
    @SuppressWarnings("rawtypes")
    void shouldAcceptEveryDeclaredMethodWhenNoBypassIsOpen() {
        assertThat(TenancyBypass.isActive()).isFalse();
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(20);
        List<String> problems = new ArrayList<>();
        for (var bean : context.getBeansOfType(Repository.class).values()) {
            for (Class<?> type : AopUtils.isAopProxy(bean) ? bean.getClass().getInterfaces() : new Class<?>[0]) {
                if (!type.getName().startsWith(APPLICATION)) continue;
                for (Method method : type.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) || method.isBridge()) continue;
                    if (!method.getDeclaringClass().getName().startsWith(APPLICATION)) continue;
                    String problem = template.execute(status -> {
                        status.setRollbackOnly();
                        return problem(bean, type, method);
                    });
                    if (problem != null) problems.add(type.getSimpleName() + "." + method.getName() + ": " + problem);
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    private @Nullable String problem(Object bean, Class<?> type, Method method) {
        Object[] args = new Object[method.getParameterCount()];
        try {
            for (int i = 0; i < args.length; i++) {
                args[i] = argument(ResolvableType.forMethodParameter(method, i, type));
            }
        } catch (Unbuildable unbuildable) {
            return "add a placeholder for " + unbuildable.getMessage();
        }
        try {
            method.setAccessible(true);
            if (method.invoke(bean, args) instanceof Stream<?> stream) stream.close();
            return null;
        } catch (InvocationTargetException | IllegalAccessException | RuntimeException failed) {
            return failure(failed);
        }
    }

    /** Other failures reached a statement, and placeholders cannot satisfy every query. */
    private static @Nullable String failure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TenancyViolationException) return t.getMessage();
            if (t instanceof ObjectRetrievalFailureException) {
                return "a placeholder failed before the method's statement ran: " + t.getMessage();
            }
        }
        return null;
    }

    private Object argument(ResolvableType declared) {
        Class<?> type = declared.resolve();
        if (type == null) throw new Unbuildable(declared.toString());
        if (Set.class.isAssignableFrom(type)) return Set.of(argument(declared.getGeneric(0)));
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (Collection.class.isAssignableFrom(type) || type == Iterable.class) {
            return List.of(argument(declared.getGeneric(0)));
        }
        if (type == Optional.class) return Optional.of(argument(declared.getGeneric(0)));
        if (type == long.class || type == Long.class || type == Object.class) return 0L;
        if (type == int.class || type == Integer.class) return 0;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == double.class || type == Double.class) return 0d;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == BigDecimal.class) return BigDecimal.ZERO;
        if (type == String.class) return "x";
        if (type == UUID.class) return new UUID(0, 0);
        if (type == Instant.class) return Instant.EPOCH;
        if (type == OffsetDateTime.class) return OffsetDateTime.parse("2020-01-01T00:00:00Z");
        if (type == LocalDateTime.class) return LocalDateTime.of(2020, 1, 1, 0, 0);
        if (type == LocalDate.class) return LocalDate.of(2020, 1, 1);
        if (type == Pageable.class) return PageRequest.of(0, 1);
        if (type == Sort.class) return Sort.unsorted();
        if (type == Limit.class) return Limit.of(1);
        if (type == Specification.class) return (Specification<Object>) (root, query, builder) -> builder.conjunction();
        if (type == byte[].class) return new byte[0];
        if (type == JsonNode.class) return JsonNodeFactory.instance.objectNode();
        if (type == ItemKind.class) return new ItemKind("source.kind");
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (type == Review.class) return review();
        if (type.isAnnotationPresent(Entity.class)) return reference(type);
        if (type.isRecord()) return record(type);
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null && permitted.length > 0) return argument(ResolvableType.forClass(permitted[0]));
        throw new Unbuildable(type.getSimpleName());
    }

    /** Report queries read this review's ids. A reference has no row to load, so they would stop before the query. */
    private static Review review() {
        var review = new Review();
        review.setId(new UUID(0, 0));
        review.setWorkspaceId(0L);
        return review;
    }

    private Object reference(Class<?> type) {
        try {
            return entities.getReference(
                    type,
                    argument(ResolvableType.forClass(
                            entities.getMetamodel().entity(type).getIdType().getJavaType())));
        } catch (RuntimeException notBuilt) {
            throw new Unbuildable(type.getSimpleName());
        }
    }

    private Object record(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = argument(ResolvableType.forType(components[i].getGenericType()));
        }
        try {
            var constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException | RuntimeException refused) {
            throw new Unbuildable(type.getSimpleName() + ", which rejected its arguments");
        }
    }
}
