package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventPublicationRepository.FailedCriteria;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;

/** Modulith owns delivery; only failed-batch ordering differs to prevent retry starvation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@WorkspaceAgnostic("The notification registry is instance-wide; each delivery listener rechecks recipient eligibility")
public class NotificationPublicationConfiguration {

    @Bean
    static BeanPostProcessor fairNotificationRetries(
            ObjectProvider<JdbcOperations> jdbc, ObjectProvider<EventSerializer> serializer) {
        return new FairRetryPostProcessor(jdbc, serializer);
    }

    private record FairRetryPostProcessor(
            ObjectProvider<JdbcOperations> jdbc, ObjectProvider<EventSerializer> serializer)
            implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof EventPublicationRepository)) return bean;
            var proxy = new ProxyFactory(bean);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                if (!invocation.getMethod().getName().equals("findFailedPublications")) {
                    return invocation.proceed();
                }
                return findFailed(jdbc.getObject(), serializer.getObject(), (FailedCriteria)
                        Objects.requireNonNull(invocation.getArguments()[0]));
            });
            return proxy.getProxy();
        }
    }

    private static List<TargetEventPublication> findFailed(
            JdbcOperations jdbc, EventSerializer serializer, FailedCriteria criteria) {
        Instant before = criteria.getPublicationDateReference();
        long limit = criteria.getMaxItemsToRead();
        String sql = """
                SELECT id, serialized_event, event_type, listener_id, publication_date,
                       last_resubmission_date, completion_attempts, status, completion_date
                  FROM event_publication
                 WHERE (status = 'FAILED' OR (status IS NULL AND completion_date IS NULL))
                """;
        var arguments = new ArrayList<Object>();
        if (before != null) {
            sql += " AND publication_date < ?";
            arguments.add(Timestamp.from(before));
        }
        sql += " ORDER BY COALESCE(last_resubmission_date, publication_date), publication_date, id";
        if (limit != -1) {
            sql += " LIMIT ?";
            arguments.add(limit);
        }
        return jdbc.query(sql, (row, index) -> new FailedPublication(row, serializer), arguments.toArray());
    }

    private static final class FailedPublication implements TargetEventPublication {
        private final UUID identifier;
        private final Instant publicationDate;
        private final PublicationTargetIdentifier target;
        private final Supplier<Object> event;
        private final @Nullable Instant lastResubmissionDate;
        private final int completionAttempts;
        private @Nullable Instant completionDate;
        private Status status;

        FailedPublication(ResultSet row, EventSerializer serializer) throws SQLException {
            identifier = Objects.requireNonNull(row.getObject("id", UUID.class));
            publicationDate =
                    Objects.requireNonNull(row.getTimestamp("publication_date")).toInstant();
            target = PublicationTargetIdentifier.of(Objects.requireNonNull(row.getString("listener_id")));
            String eventType = Objects.requireNonNull(row.getString("event_type"));
            String serialized = Objects.requireNonNull(row.getString("serialized_event"));
            event = SingletonSupplier.of(() -> serializer.deserialize(
                    serialized,
                    ClassUtils.resolveClassName(
                            eventType, NotificationPublicationConfiguration.class.getClassLoader())));
            Timestamp resubmitted = row.getTimestamp("last_resubmission_date");
            lastResubmissionDate = resubmitted == null ? null : resubmitted.toInstant();
            completionAttempts = row.getInt("completion_attempts");
            Timestamp completed = row.getTimestamp("completion_date");
            completionDate = completed == null ? null : completed.toInstant();
            String storedStatus = row.getString("status");
            status = storedStatus != null
                    ? Status.valueOf(storedStatus)
                    : completionDate == null ? Status.PROCESSING : Status.COMPLETED;
        }

        @Override
        public UUID getIdentifier() {
            return identifier;
        }

        @Override
        public Object getEvent() {
            return event.get();
        }

        @Override
        public Instant getPublicationDate() {
            return publicationDate;
        }

        @Override
        public Optional<Instant> getCompletionDate() {
            return Optional.ofNullable(completionDate);
        }

        @Override
        public PublicationTargetIdentifier getTargetIdentifier() {
            return target;
        }

        @Override
        public void markCompleted(Instant instant) {
            completionDate = instant;
            status = Status.COMPLETED;
        }

        @Override
        public Status getStatus() {
            return status;
        }

        @Override
        public @Nullable Instant getLastResubmissionDate() {
            return lastResubmissionDate;
        }

        @Override
        public int getCompletionAttempts() {
            return completionAttempts;
        }
    }
}
