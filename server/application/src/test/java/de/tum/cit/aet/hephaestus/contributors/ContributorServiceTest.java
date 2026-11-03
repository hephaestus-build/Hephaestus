package de.tum.cit.aet.hephaestus.contributors;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class ContributorServiceTest extends BaseUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldListTheContributorsThePackagingBuildRecorded() {
        var recorded = new ByteArrayResource("""
                [{"id":1,"login":"ada","name":"Ada Lovelace","avatarUrl":"https://example.com/a.png",
                  "htmlUrl":"https://github.com/ada","contributions":42}]
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(new ContributorService(mapper, recorded).getGlobalContributors())
                .containsExactly(new ContributorDTO(
                        1L, "ada", "Ada Lovelace", "https://example.com/a.png", "https://github.com/ada", 42));
    }

    @Test
    void shouldListNobodyWhenTheBuildRecordedNoList() {
        var absent = new ClassPathResource("contributors-not-recorded.json");

        assertThat(new ContributorService(mapper, absent).getGlobalContributors())
                .isEmpty();
    }
}
