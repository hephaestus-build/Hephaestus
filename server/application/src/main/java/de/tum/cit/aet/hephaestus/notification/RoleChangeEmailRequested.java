package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;

/** One recipient's email about a role change: the affected account, or an owner or instance admin who oversees it. */
record RoleChangeEmailRequested(RoleChangedEvent change, long accountId) {}
