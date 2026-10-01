package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class PersonDataRegistry {
    private final List<PersonDataContributor> stores;

    public PersonDataRegistry(List<PersonDataCatalog> catalogs) {
        for (PersonDataCatalog catalog : catalogs) {
            PersonDataStores declaration =
                    org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                            catalog.getClass(), PersonDataStores.class);
            if (declaration == null
                    || !new TreeSet<>(Arrays.asList(declaration.value()))
                            .equals(new TreeSet<>(catalog.contributors().stream()
                                    .map(PersonDataContributor::store)
                                    .toList())))
                throw new IllegalStateException("Person data catalog does not implement its store declaration");
        }
        stores = catalogs.stream()
                .flatMap(c -> c.contributors().stream())
                .sorted(Comparator.comparingInt(PersonDataContributor::getOrder)
                        .thenComparing(PersonDataContributor::store))
                .toList();
        if (stores.stream().map(PersonDataContributor::store).distinct().count() != stores.size())
            throw new IllegalStateException("Duplicate person data stores");
    }

    public List<PersonDataContributor> stores() {
        return stores;
    }

    public Map<String, PersonDataSelection> select(PersonScope scope) {
        Map<String, PersonDataSelection> selected = new TreeMap<>();
        for (var store : stores) selected.put(store.store(), store.select(scope));
        return Collections.unmodifiableMap(selected);
    }

    public Map<String, Long> counts(Map<String, PersonDataSelection> selected) {
        Map<String, Long> counts = new TreeMap<>();
        selected.forEach((k, v) -> counts.put(k, (long) v.rows().size()));
        return counts;
    }
}
