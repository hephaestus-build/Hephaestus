package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.List;
import java.util.function.LongFunction;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.LOWEST_PRECEDENCE)
public class StubInstallationRepositoryEnumerator implements InstallationRepositoryEnumerator {

    private LongFunction<List<InstallationRepository>> response = ignored -> List.of();

    public void returns(List<InstallationRepository> repositories) {
        var captured = List.copyOf(repositories);
        this.response = ignored -> captured;
    }

    public void reset() {
        response = ignored -> List.of();
    }

    public void answers(LongFunction<List<InstallationRepository>> response) {
        this.response = response;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public List<InstallationRepository> enumerate(long installationId) {
        return response.apply(installationId);
    }
}
