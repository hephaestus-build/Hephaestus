package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.security.ScmEgressProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import java.util.List;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

/** The production fetch check, with an empty operator list. */
public final class GitTransportFixtures {
    private GitTransportFixtures() {}

    public static PersonDataCopyFence copyFence() {
        return Mockito.mock(PersonDataCopyFence.class, Answers.RETURNS_MOCKS);
    }

    public static GuardedGitTransport guarded() {
        return new GuardedGitTransport(new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of())));
    }

    /**
     * The production fetch check with the e2e SCM simulator on this loopback port: the one origin that may serve Git
     * over plain HTTP, as the e2e profile configures it.
     */
    public static GuardedGitTransport simulator(int port) {
        var environment = new MockEnvironment().withProperty("hephaestus.e2e.scm-origin", "http://127.0.0.1:" + port);
        environment.setActiveProfiles("e2e");
        return new GuardedGitTransport(
                new ScmServerEndpointPolicy(environment, WebClient.builder(), new ScmEgressProperties(List.of())));
    }
}
