package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/** Loads the production journal in a test context that does not scan the privacy package. */
@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(
        basePackageClasses = PersonDataService.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PrivacyJournal.class))
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
public class PrivacyJournalTestConfiguration {}
