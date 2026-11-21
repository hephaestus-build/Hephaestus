package de.tum.cit.aet.hephaestus.practices.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where a practice is edited: in Hephaestus, or in the workspace's practice repository (ADR 0118). A practice edited
 * in the repository takes its definition and group from its folder on the default branch, and the consoles and MCP
 * write tools refuse to change them.
 */
@Schema(name = "PracticeEditedIn")
public enum PracticeEditedIn {
    HEPHAESTUS,
    REPOSITORY
}
