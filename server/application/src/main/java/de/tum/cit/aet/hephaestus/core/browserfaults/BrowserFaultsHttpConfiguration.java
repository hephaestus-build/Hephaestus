package de.tum.cit.aet.hephaestus.core.browserfaults;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PayloadSizeFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** The endpoint takes reports from readers who are not signed in, so its body is bounded before it is read. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(BrowserFaultRecorder.ENABLED)
public class BrowserFaultsHttpConfiguration {

    /** 20 faults of 10 frames each fit in about 13 KiB. */
    static final long MAX_REPORT_BYTES = 16 * 1024;

    /** The filter answers before the HTTP observation starts, so it counts its own refusals. */
    @Bean
    FilterRegistrationBean<PayloadSizeFilter> browserFaultsPayloadSizeFilter(MeterRegistry registry) {
        PayloadSizeFilter filter = new PayloadSizeFilter(MAX_REPORT_BYTES) {
            @Override
            protected void refused(HttpServletRequest request, String reason) {
                Counter.builder(CoreMetrics.BROWSER_FAULTS_REFUSED)
                        .description("Browser fault reports refused before they were read")
                        .tag("reason", reason)
                        .register(registry)
                        .increment();
            }
        };
        FilterRegistrationBean<PayloadSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/browser-faults");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
