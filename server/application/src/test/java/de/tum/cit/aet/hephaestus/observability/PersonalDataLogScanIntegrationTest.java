package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PersonalDataLogScanIntegrationTest extends BaseIntegrationTest {

    @Test
    void shouldScanARecordThatARunningContextLogs() {
        var scan = PersonalDataLogScan.current();
        assertThat(scan).as("the launcher session started the scan").isNotNull();
        long before = scan.records();

        LoggerFactory.getLogger("de.tum.cit.aet.hephaestus.observability.Probe").info("a record without findings");

        assertThat(scan.records())
                .as("Spring Boot's logging setup left the scan attached")
                .isEqualTo(before + 1);
    }
}
