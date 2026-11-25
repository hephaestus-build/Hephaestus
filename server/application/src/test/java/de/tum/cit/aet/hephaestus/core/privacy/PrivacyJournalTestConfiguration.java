package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/** Loads the production journal in a test context that does not scan the privacy package. */
@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(
        basePackageClasses = PersonDataService.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PrivacyJournal.class))
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import(PrivacyJournalShipping.class)
public class PrivacyJournalTestConfiguration {

    @Bean
    SimpleMeterRegistry privacyJournalMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
