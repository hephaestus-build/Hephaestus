package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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

/**
 * Modulith owns delivery. Only the selection of failed publications differs: least recently retried first, so no failing
 * batch takes every pass, and without the exhausted ones ({@link NotificationRedeliveryJob#MAX_ATTEMPTS}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
public class NotificationPublicationConfiguration {

    @Bean
    PublicationCounts publicationCounts(JdbcOperations jdbc) {
        return new PublicationCounts(jdbc);
    }

    /** The open publications of one listener: those exhausted, and the others still open past the overdue bound. */
    public record OpenPublications(String listenerId, long exhausted, long overdue) {}

    /**
     * Counts the open publications by listener in the database. It reads no payload, so it also counts a row whose
     * event type an upgrade removed. A failed row is exhausted at once, however young, with the predicate that the retry
     * uses; a running last attempt can still end.
     */
    public static final class PublicationCounts {
        private final JdbcOperations jdbc;

        PublicationCounts(JdbcOperations jdbc) {
            this.jdbc = jdbc;
        }

        public List<OpenPublications> byListener(Duration overdueAfter) {
            String exhaustedRow = FAILED + " AND completion_attempts >= ?";
            String sql = """
                    SELECT listener_id,
                           count(*) FILTER (WHERE %1$s) AS exhausted,
                           count(*) FILTER (WHERE NOT (%1$s)
                                              AND publication_date < clock_timestamp() - make_interval(mins => CAST(? AS integer)))
                               AS overdue
                      FROM event_publication
                     WHERE completion_date IS NULL
                     GROUP BY listener_id
                    """.formatted(exhaustedRow);
            return jdbc.query(
                    sql,
                    (row, index) -> new OpenPublications(
                            row.getString("listener_id"), row.getLong("exhausted"), row.getLong("overdue")),
                    NotificationRedeliveryJob.MAX_ATTEMPTS,
                    NotificationRedeliveryJob.MAX_ATTEMPTS,
                    overdueAfter.toMinutes());
        }
    }

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

    /** The publications that a retry may take: failed, or open in the state that an older Modulith wrote. */
    static final String FAILED = "(status = 'FAILED' OR (status IS NULL AND completion_date IS NULL))";

    private static List<TargetEventPublication> findFailed(
            JdbcOperations jdbc, EventSerializer serializer, FailedCriteria criteria) {
        Instant before = criteria.getPublicationDateReference();
        long limit = criteria.getMaxItemsToRead();
        String sql = """
                SELECT id, serialized_event, event_type, listener_id, publication_date,
                       last_resubmission_date, completion_attempts, status, completion_date
                  FROM event_publication
                 WHERE %s
                   AND completion_attempts < ?
                   AND COALESCE(last_resubmission_date, publication_date)
                       + make_interval(mins => LEAST(? * power(2, GREATEST(completion_attempts - 1, 0)), ?)::int)
                       < clock_timestamp()
                """.formatted(FAILED);
        // The backoff counts from the last attempt, in the SQL before the batch limit: Modulith's minimum age counts
        // from the publication, so servers that take turns would otherwise retry a row every few minutes. The database
        // clock decides, so the clocks of the servers that take turns cannot disagree about a row.
        var arguments = new ArrayList<Object>();
        arguments.add(NotificationRedeliveryJob.MAX_ATTEMPTS);
        arguments.add(NotificationRedeliveryJob.FIRST_BACKOFF.toMinutes());
        arguments.add(NotificationRedeliveryJob.MAX_BACKOFF.toMinutes());
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
