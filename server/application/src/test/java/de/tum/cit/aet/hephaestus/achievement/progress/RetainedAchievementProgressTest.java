package de.tum.cit.aet.hephaestus.achievement.progress;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetainedAchievementProgressTest extends BaseUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldDeserializeStoredBinaryProgress() {
        assertThat(mapper.readValue(
                        "{\"type\":\"BinaryAchievementProgress\",\"unlocked\":true}", AchievementProgress.class))
                .isEqualTo(new BinaryAchievementProgress(true));
    }

    @Test
    void shouldDeserializeStoredLinearProgress() {
        assertThat(mapper.readValue(
                        "{\"type\":\"LinearAchievementProgress\",\"current\":3,\"target\":10}",
                        AchievementProgress.class))
                .isEqualTo(new LinearAchievementProgress(3, 10));
    }
}
