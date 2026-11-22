package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Collection;
import java.util.Map;

/**
 * Whether provider threads on work items are resolved, as Hephaestus's mirror of the provider last recorded them. A
 * thread is named as a feedback copy names it: a GitHub review thread by its node id, a GitLab discussion by its global
 * id.
 */
public interface MirroredThreadStateQuery {
    /**
     * True for a resolved thread, false for an unresolved one, keyed by thread ref. A ref without a mirrored thread on
     * one of {@code itemIds} in the workspace is absent.
     */
    Map<String, Boolean> resolved(long workspaceId, Collection<Long> itemIds, Collection<String> threadRefs);
}
