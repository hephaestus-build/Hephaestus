package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import io.swagger.v3.oas.annotations.media.Schema;

/** What adopting a whole catalog group would do to one practice in it. */
// The DTO that carries it already publishes this class name.
@Schema(name = "CatalogAdoptionAction")
public enum CatalogGroupPracticeAction {
    /** Not in the workspace yet: adoption copies it in. */
    ADD,
    /** Already here under a different group: adoption moves it, leaving the definition alone. */
    MOVE_TO_GROUP,
    /** Already here in this group: adoption leaves it exactly as it is. */
    KEEP,
    /** Cannot be added — see {@link CatalogAdoptionAvailability#SLUG_CONFLICT}. */
    BLOCKED,
}
