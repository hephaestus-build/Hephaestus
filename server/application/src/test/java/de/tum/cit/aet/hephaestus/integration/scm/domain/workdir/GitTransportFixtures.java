package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.core.security.ScmEgressProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import java.util.List;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

/** The production fetch check, with an empty operator list. */
public final class GitTransportFixtures {
    private GitTransportFixtures() {}

    public static GuardedGitTransport guarded() {
        return new GuardedGitTransport(new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of())));
    }
}
