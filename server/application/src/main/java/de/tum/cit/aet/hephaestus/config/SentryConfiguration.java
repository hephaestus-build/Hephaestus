package de.tum.cit.aet.hephaestus.config;

import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.sentry.EventProcessor;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

// `dsn` defaults to "" when SENTRY_DSN is unset; @ConditionalOnProperty(name = "dsn") would
// still match the empty string. Match only when the DSN is non-blank.
@Configuration
@ConditionalOnExpression("!'${hephaestus.sentry.dsn:}'.isBlank()")
public class SentryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SentryConfiguration.class);

    private final Environment environment;
    private final String hephaestusVersion;
    private final SentryProperties sentryProperties;

    public SentryConfiguration(
            Environment environment,
            @Value("${spring.application.version}") String hephaestusVersion,
            SentryProperties sentryProperties) {
        this.environment = environment;
        this.hephaestusVersion = hephaestusVersion;
        this.sentryProperties = sentryProperties;
    }

    @PostConstruct
    public void init() {
        if (environment.matchesProfiles("specs")) {
            log.info("Skipped Sentry initialization: reason=specs_profile");
            return;
        }

        try {
            final String dsn = sentryProperties.dsn() + "?stacktrace.app.packages=de.tum.cit.aet.hephaestus";

            Sentry.init(options -> {
                options.setDsn(dsn);
                options.setSendDefaultPii(false);
                options.setBeforeSend((event, hint) -> {
                    event.setUser(null);
                    event.setRequest(null);
                    event.setBreadcrumbs(null);
                    return event;
                });
                options.setEnvironment(sentryProperties.environment());
                options.setRelease(hephaestusVersion);
                // Links a Sentry event to its JSON log lines; the tag survives the beforeSend scrub,
                // which removes only user, request, and breadcrumb context.
                options.addEventProcessor(new EventProcessor() {
                    @Override
                    public SentryEvent process(SentryEvent event, Hint hint) {
                        String traceId = MDC.get(StructuredLogKeys.TRACE_ID);
                        if (traceId != null) event.setTag(StructuredLogKeys.TRACE_ID, traceId);
                        return event;
                    }
                });
            });

            log.info("Initialized Sentry");
        } catch (Exception ex) {
            log.error("Failed to initialize Sentry", ex);
        }
    }
}
