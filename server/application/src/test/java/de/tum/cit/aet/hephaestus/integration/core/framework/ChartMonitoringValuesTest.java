package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An operator gets a value the server would refuse at start as an error at install, not as a failing Pod. */
class ChartMonitoringValuesTest extends BaseUnitTest {

    private static final Path SCHEMA = Path.of("../../deploy/chart/hephaestus/values.schema.json");

    @ParameterizedTest
    @CsvSource({"timeframeDays", "cooldownMinutes"})
    void theChartAdmitsWhatTheServerAdmits(String value) throws Exception {
        JsonNode schema = JsonMapper.builder()
                .build()
                .readTree(Files.readString(SCHEMA))
                .at("/properties/config/properties/monitoring/properties/" + value);
        Field field = SyncSchedulerProperties.class.getDeclaredField(value);

        assertThat(schema.get("minimum").asLong())
                .isEqualTo(field.getAnnotation(Min.class).value());
        assertThat(schema.get("maximum").asLong())
                .isEqualTo(field.getAnnotation(Max.class).value());
    }
}
