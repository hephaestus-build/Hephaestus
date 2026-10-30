package de.tum.cit.aet.hephaestus.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlackHephaestusUiLinksTest extends BaseUnitTest {

    private static final UUID ENTRY = UUID.fromString("0f8a6c1e-3b0d-4a43-9f43-3bd1d5a0e2c4");

    @Test
    void userSettingsUrl_linksToPersonalSettings() {
        SlackHephaestusUiLinks links =
                new SlackHephaestusUiLinks("https://heph.example/", new WorkspaceSubdomainProperties(false, ""));

        assertThat(links.userSettingsUrl()).isEqualTo("https://heph.example/settings");
    }

    @Test
    void shouldLinkAHephMessageOnTheWorkspaceAddressWhenSubdomainsAreEnabled() {
        SlackHephaestusUiLinks apex =
                new SlackHephaestusUiLinks("https://heph.example/", new WorkspaceSubdomainProperties(false, ""));
        SlackHephaestusUiLinks subdomain = new SlackHephaestusUiLinks(
                "https://heph.example/", new WorkspaceSubdomainProperties(true, "heph.example"));

        assertThat(apex.mentorEntryUrl("acme", ENTRY))
                .isEqualTo("https://heph.example/w/acme/conversation?entry=" + ENTRY);
        assertThat(subdomain.mentorEntryUrl("acme", ENTRY))
                .isEqualTo("https://acme.heph.example/conversation?entry=" + ENTRY);
    }
}
