package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import org.jspecify.annotations.Nullable;

/**
 * How a piece of reviewed work is named to a reader, on every surface: the way its provider names it. A
 * GitHub pull request or any issue is {@code #22}, a GitLab merge request {@code !425}, a conversation its
 * channel, a document its title. The title is carried beside the label only where it adds a name the label
 * does not already say, and where the work sits — a repository, a collection — beside both. With no run to read the name off, or a run that names other work, the kind alone is
 * said, never a number that was not there.
 */
public final class ReviewedWorkLabels {

    private ReviewedWorkLabels() {}

    /** No kind or no anchored work means there is nothing to name, so there is no reference either. */
    public static @Nullable ReviewedWorkRefDTO refOrNull(
            @Nullable ArtifactKind kind, @Nullable Long id, @Nullable Target target) {
        return kind == null || id == null ? null : ref(kind, id.longValue(), target);
    }

    public static ReviewedWorkRefDTO ref(ArtifactKind kind, long id, @Nullable Target target) {
        if (target == null || !target.type().equals(kind) || (target.id() != null && target.id() != id)) {
            return new ReviewedWorkRefDTO(Long.toString(id), kind, null, fallbackLabel(kind, null), null, null, null);
        }
        return new ReviewedWorkRefDTO(
                Long.toString(id),
                kind,
                target.provider(),
                label(kind, target),
                title(kind, target),
                target.url(),
                target.container());
    }

    /**
     * The work as the mirror names it, for a surface that has no run to read the name off. An identity no
     * resolver could name carries no provider, and is said by its kind alone.
     */
    public static ReviewedWorkRefDTO ref(ArtifactIdentity identity) {
        ArtifactKind kind = identity.kind();
        long id = identity.id();
        if (identity.provider() == null) {
            return ref(kind, id, null);
        }
        boolean conversation = kind.equals(ArtifactKinds.CONVERSATION_THREAD);
        return ref(
                kind,
                id,
                new Target(
                        kind,
                        id,
                        identity.provider(),
                        identity.number(),
                        identity.title(),
                        conversation ? null : identity.container(),
                        conversation ? identity.container() : null,
                        identity.url()));
    }

    /**
     * A conversation thread has no title of its own, the target's being the kind's name; a document's title
     * is already its label, so carrying it twice would print it twice.
     */
    private static @Nullable String title(ArtifactKind kind, Target target) {
        return kind.equals(ArtifactKinds.CONVERSATION_THREAD) || kind.equals(ArtifactKinds.DOCUMENT)
                ? null
                : target.title();
    }

    private static String label(ArtifactKind kind, Target target) {
        if (kind.equals(ArtifactKinds.PULL_REQUEST)) {
            Integer number = target.number();
            String prefix = target.provider() == IntegrationKind.GITLAB ? "!" : "#";
            return number == null ? fallbackLabel(kind, target.provider()) : prefix + number;
        }
        if (kind.equals(ArtifactKinds.ISSUE)) {
            Integer number = target.number();
            return number == null ? fallbackLabel(kind, target.provider()) : "#" + number;
        }
        if (kind.equals(ArtifactKinds.CONVERSATION_THREAD)) {
            String channel = target.channelName();
            return channel == null ? fallbackLabel(kind, target.provider()) : "#" + channel;
        }
        return target.title();
    }

    /** The kind's noun at the provider: the same kind of work is a merge request on GitLab. */
    private static String fallbackLabel(ArtifactKind kind, @Nullable IntegrationKind provider) {
        if (kind.equals(ArtifactKinds.PULL_REQUEST)) {
            if (provider == null) {
                return "Pull or merge request";
            }
            return provider == IntegrationKind.GITLAB ? "Merge request" : "Pull request";
        }
        if (kind.equals(ArtifactKinds.ISSUE)) {
            return "Issue";
        }
        if (kind.equals(ArtifactKinds.CONVERSATION_THREAD)) {
            return "Conversation";
        }
        if (kind.equals(ArtifactKinds.DOCUMENT)) {
            return "Document";
        }
        return "Other work";
    }
}
