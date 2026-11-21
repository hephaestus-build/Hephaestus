package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;

/**
 * One source per practice (ADR 0118): a practice edited in the practice repository takes its definition and group
 * from its folder, so the consoles and the MCP write tools cannot change them. Its feedback mode and position stay
 * the workspace's.
 */
final class PracticeEditing {
    private PracticeEditing() {}

    static void requireEditedInHephaestus(Practice practice) {
        if (practice.getEditedIn() == PracticeEditedIn.REPOSITORY) throw new PracticeEditedInRepositoryException();
    }
}
