package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;

/** The owning module registers all of its stores together. */
public interface PersonDataCatalog {
    List<PersonDataContributor> contributors();
}
