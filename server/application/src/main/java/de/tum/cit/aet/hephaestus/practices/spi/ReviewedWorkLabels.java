package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import org.jspecify.annotations.Nullable;

/**
 * How a piece of reviewed work is named to a reader, on every surface: the way its provider names it. A
 * GitHub pull request or any issue is {@code #22}, a GitLab merge request {@code !425}, a conversation its
 * channel, a document its title. With no run to read the name off, or a run that names other work, the kind
 * alone is said, never a number that was not there.
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
                target.repositoryName());
    }

    /** A conversation thread has no title of its own; the target's is the kind's name, not the work's. */
    private static @Nullable String title(ArtifactKind kind, Target target) {
        return kind.equals(ArtifactKinds.CONVERSATION_THREAD) ? null : target.title();
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
        return "Reviewed work";
    }
}
