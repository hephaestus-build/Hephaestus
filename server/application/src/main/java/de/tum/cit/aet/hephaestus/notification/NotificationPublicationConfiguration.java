package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.database.publication.EventPublicationStore;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
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
    static BeanPostProcessor fairNotificationRetries(
            ObjectProvider<EventPublicationStore> store, ObjectProvider<EventSerializer> serializer) {
        return new FairRetryPostProcessor(store, serializer);
    }

    private record FairRetryPostProcessor(
            ObjectProvider<EventPublicationStore> store, ObjectProvider<EventSerializer> serializer)
            implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof EventPublicationRepository)) return bean;
            var proxy = new ProxyFactory(bean);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                if (!invocation.getMethod().getName().equals("findFailedPublications")) {
                    return invocation.proceed();
                }
                return findFailed(store.getObject(), serializer.getObject(), (FailedCriteria)
                        Objects.requireNonNull(invocation.getArguments()[0]));
            });
            return proxy.getProxy();
        }
    }

    private static List<TargetEventPublication> findFailed(
            EventPublicationStore store, EventSerializer serializer, FailedCriteria criteria) {
        // The backoff counts from the last attempt, before the batch limit: Modulith's minimum age counts from the
        // publication, so servers that take turns would otherwise retry a row every few minutes.
        return store
                .failedDueForRetry(
                        NotificationRedeliveryJob.MAX_ATTEMPTS,
                        NotificationRedeliveryJob.FIRST_BACKOFF,
                        NotificationRedeliveryJob.MAX_BACKOFF,
                        criteria.getPublicationDateReference(),
                        criteria.getMaxItemsToRead())
                .stream()
                .<TargetEventPublication>map(row -> new FailedPublication(row, serializer))
                .toList();
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

        FailedPublication(EventPublicationStore.StoredPublication row, EventSerializer serializer) {
            identifier = row.id();
            publicationDate = row.publicationDate();
            target = PublicationTargetIdentifier.of(row.listenerId());
            event = SingletonSupplier.of(() -> serializer.deserialize(
                    row.serializedEvent(),
                    ClassUtils.resolveClassName(
                            row.eventType(), NotificationPublicationConfiguration.class.getClassLoader())));
            lastResubmissionDate = row.lastResubmissionDate();
            completionAttempts = row.completionAttempts();
            completionDate = row.completionDate();
            String storedStatus = row.status();
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
