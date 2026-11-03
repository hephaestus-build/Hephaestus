package de.tum.cit.aet.hephaestus.practices.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ResultCoverageTest {

    private final JsonMapper mapper = new JsonMapper();

    @Test
    void shouldReadStoredHostCoverage() {
        var stored = mapper.readTree("""
                {"source":"host","complete":true,"reads":1,"truncatedReads":0,"failedReads":0,"receipts":[]}
                """);
        assertThat(ResultCoverage.stored(mapper, stored)).isEqualTo(new HostCoverage(true, null, 1, 0, 0, List.of()));
    }

    @Test
    void shouldReadNoCoverageAndCoverageThatAScriptClaimedAsNone() {
        var claimed = mapper.readTree("""
                {"source":"claimed","complete":true,"selected":1,"checked":1,"total":1}
                """);
        assertThat(ResultCoverage.stored(mapper, claimed)).isNull();
        assertThat(ResultCoverage.stored(mapper, null)).isNull();
    }
}
