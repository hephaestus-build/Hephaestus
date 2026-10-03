package de.tum.cit.aet.hephaestus.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class SlackHephaestusUiLinksTest extends BaseUnitTest {

    @Test
    void userSettingsUrl_linksToPersonalSettings() {
        SlackHephaestusUiLinks links = new SlackHephaestusUiLinks("https://heph.example/");

        assertThat(links.userSettingsUrl()).isEqualTo("https://heph.example/settings");
    }
}
