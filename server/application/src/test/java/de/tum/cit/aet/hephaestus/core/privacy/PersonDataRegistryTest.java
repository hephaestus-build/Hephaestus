package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PersonDataRegistryTest extends BaseUnitTest {
    @Test
    void shouldKeepValidatedContributorsWhenCatalogProducesDifferentInstances() {
        var registry = new PersonDataRegistry(List.of(new ChangingCatalog()));

        assertThat(registry.stores()).extracting(PersonDataContributor::store).containsExactly("profile");
        assertThat(registry.select(
                        new PersonScope(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())))
                .containsOnlyKeys("profile")
                .containsValue(new PersonDataSelection(List.of(new PersonDataSelection.RowKey(Map.of("id", "42")))));
    }

    @Test
    void shouldRejectDuplicateStoresAcrossCatalogs() {
        assertThatThrownBy(() -> new PersonDataRegistry(List.of(new ProfileCatalog(), new ProfileCatalog())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate person data stores");
    }

    @Test
    void shouldRejectContributorsMissingFromDeclaration() {
        assertThatThrownBy(() -> new PersonDataRegistry(List.of(new WrongCatalog())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Person data catalog does not implement its store declaration");
    }

    @PersonDataStores({"profile"})
    private static class ProfileCatalog implements PersonDataCatalog {
        @Override
        public List<PersonDataContributor> contributors() {
            return List.of(new SelectedStore("profile"));
        }
    }

    @PersonDataStores({"profile"})
    private static final class ChangingCatalog extends ProfileCatalog {
        private boolean produced;

        @Override
        public List<PersonDataContributor> contributors() {
            if (produced) return List.of(new SelectedStore("undeclared"));
            produced = true;
            return super.contributors();
        }
    }

    @PersonDataStores({"profile"})
    private static final class WrongCatalog implements PersonDataCatalog {
        @Override
        public List<PersonDataContributor> contributors() {
            return List.of(new SelectedStore("undeclared"));
        }
    }

    private record SelectedStore(String store) implements PersonDataContributor {
        @Override
        public PersonDataSelection select(PersonScope person) {
            return new PersonDataSelection(List.of(new PersonDataSelection.RowKey(Map.of("id", "42"))));
        }

        @Override
        public List<JsonNode> export(PersonDataSelection selection) {
            return List.of();
        }

        @Override
        public long erase(PersonDataSelection selection) {
            return selection.rows().size();
        }
    }
}
