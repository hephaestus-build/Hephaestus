package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What each store projects for a preview's frozen selection, keyed by store under {@code stores}. No admin route
 * returns it (#4966): tests read it to prove which rows a store selects.
 */
public final class PersonDataProjections {

    private PersonDataProjections() {}

    public static JsonNode of(ApplicationContext beans, UUID id) {
        var mapper = beans.getBean(ObjectMapper.class);
        var tx = new TransactionTemplate(beans.getBean(PlatformTransactionManager.class));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(tx.execute(status -> {
            PersonDataRequest request =
                    beans.getBean(PersonDataService.class).get(id).request();
            Map<String, PersonDataSelection> selected = mapper.readValue(
                    Objects.requireNonNull(request.getSelectionsJson()),
                    new TypeReference<Map<String, PersonDataSelection>>() {});
            PersonScope scope = mapper.readValue(Objects.requireNonNull(request.getScopeJson()), PersonScope.class);
            // Row-level security shows a person's own rows only to a transaction bound to that person.
            beans.getBean(PersonDataScopeResolver.class).bind(scope);
            var bundle = mapper.createObjectNode();
            var stores = bundle.putObject("stores");
            for (var store : beans.getBean(PersonDataRegistry.class).stores())
                stores.set(
                        store.store(),
                        mapper.valueToTree(store.export(
                                selected.getOrDefault(store.store(), new PersonDataSelection(List.of())), scope)));
            return bundle;
        }));
    }
}
