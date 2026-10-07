package de.tum.cit.aet.hephaestus.workspace.events;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that changes whose work a workspace may count: a member hidden or shown, a
 * repository hidden from contributions or shown again, or an account's AI choice. A null workspace means every
 * workspace, since an AI choice holds in each one.
 */
public record WorkspacePrivacyChangedEvent(@Nullable Long workspaceId) {}
