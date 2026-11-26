package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Observation;
import java.util.Map;

public final class RepositoryObservationTestFixtures {
    private RepositoryObservationTestFixtures() {}

    public static void confirm(RepositoryObservationStore store, ReadersRead read, boolean publicAnswer) {
        var visibility = publicAnswer ? Repository.Visibility.PUBLIC : Repository.Visibility.PRIVATE;
        store.observe(read, Observation.read(read, visibility, Map.of()));
    }
}
