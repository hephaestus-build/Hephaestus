package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import java.util.Set;

/**
 * The ingested event types the shared SCM domain's signals come from.
 *
 * <p>Kept in the domain rather than either vendor package because a signal's provenance spans both:
 * {@code scm.pull_request.reviewed} is raised by a GitHub review event and by GitLab merge-request and
 * note events. Keys are repeated from the handlers on purpose — the bootstrap resolves each against the
 * handler registry, so a drift here fails the boot instead of quietly declaring undeliverable provenance.
 */
final class ScmEventSources {

    static final EventTypeKey GITHUB_PULL_REQUEST = new EventTypeKey(IntegrationKind.GITHUB, "repository.pull_request");
    static final EventTypeKey GITHUB_PULL_REQUEST_REVIEW =
            new EventTypeKey(IntegrationKind.GITHUB, "repository.pull_request_review");
    static final EventTypeKey GITHUB_ISSUES = new EventTypeKey(IntegrationKind.GITHUB, "repository.issues");

    static final EventTypeKey GITLAB_MERGE_REQUEST = new EventTypeKey(IntegrationKind.GITLAB, "merge_request");
    static final EventTypeKey GITLAB_NOTE = new EventTypeKey(IntegrationKind.GITLAB, "note");
    static final EventTypeKey GITLAB_ISSUE = new EventTypeKey(IntegrationKind.GITLAB, "issue");

    /**
     * Reads its revision scheme from {@link ScmSignals} rather than taking one, so a signal can't be
     * deduplicated by one rule and re-measured by another. A signal name is persisted in
     * {@code artifact_signal.signal_name} as part of the dedup key, so renaming one re-runs every
     * occurrence already recorded under the old name.
     */
    static Signal declare(SignalName name, String displayName, Set<EventTypeKey> producedBy) {
        return new Signal(name, displayName, producedBy, ScmSignals.revisionScheme(name));
    }

    /** As {@link #declare}, for a signal a new practice on this artifact should start out watching. */
    static Signal declareRecommended(SignalName name, String displayName, Set<EventTypeKey> producedBy) {
        return new Signal(name, displayName, producedBy, ScmSignals.revisionScheme(name), true);
    }

    /** A real signal whose provider provenance is declared, but which only Hephaestus may use as a repair occasion. */
    static Signal declareInternal(SignalName name, String displayName, Set<EventTypeKey> producedBy) {
        return new Signal(name, displayName, producedBy, ScmSignals.revisionScheme(name), false, false, false);
    }

    /**
     * As {@link #declare}, for the signal a person raises by asking for a review now. No {@code producedBy}
     * — a request is raised inside Hephaestus, and the empty provenance stops a vendor from claiming it can
     * deliver one. Not offered to authors either: a practice binds occasions it measures, and "somebody
     * asked" is not one — the request runs every practice on the kind instead.
     */
    static Signal declareManualRequest(SignalName name, String displayName) {
        return new Signal(name, displayName, Set.of(), ScmSignals.revisionScheme(name), false, true);
    }

    private ScmEventSources() {}
}
