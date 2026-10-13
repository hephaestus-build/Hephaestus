package de.tum.cit.aet.hephaestus.integration.slack.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackOnboardingServiceTest extends BaseUnitTest {

    private final SlackOnboardingService service = new SlackOnboardingService("https://heph.example.com/", "");

    @Test
    void linkUrl_isTheAuthenticatedLinkModeDeepLink() {
        assertThat(service.linkUrl())
                .isEqualTo("https://heph.example.com/auth/login?provider=slack&mode=link&returnTo=/settings");
    }

    @Test
    void linkUrl_includesApiBasePathWhenAppServerIsMountedUnderApi() {
        SlackOnboardingService apiMounted = new SlackOnboardingService("https://heph.example.com/", "/api");

        assertThat(apiMounted.linkUrl())
                .isEqualTo("https://heph.example.com/api/auth/login?provider=slack&mode=link&returnTo=/settings");
    }

    @Test
    void linkCtaBlocks_carryTheDeepLinkButton() {
        List<LayoutBlock> blocks = service.linkCtaBlocks();

        assertThat(blocks).isNotEmpty();
        assertThat(blocks.toString()).contains("/auth/login?provider=slack&mode=link");
    }
}
