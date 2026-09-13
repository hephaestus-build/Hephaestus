package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;

record WorkspaceAlertEmailRequested(IntegrationAttentionChangedEvent change, long accountId) {}
