package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedOccasion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedWork;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a pull request or submitted review occasion retains, read in the transaction that records it: the version it
 * pins, the state and roles a review is admitted with, and the work record and review decision its capture stages. It
 * is the mirror as this occasion stored it, observed at {@code observedAt}; it is no provider snapshot of an earlier
 * moment.
 *
 * @param itemVersion null until the occasion pins its version; a GitLab occasion fills it with its diff base later
 * @param linkedContextVersion the version of the linked issues' material that {@code itemVersion} folds in
 * @param decision the submitted review's decision and its reviewer, as the review occasion stored them; null for a pull
 *     request occasion
 * @param participants every person whose identity or words {@code record} or {@code decision} copies; core erases by
 *     them
 */
public record ScmOccasionEvidence(
        int schema,
        String kind,
        long sourceId,
        @Nullable String itemVersion,
        @Nullable String baseVersion,
        @Nullable String linkedContextVersion,
        IntegrationRef integration,
        Map<String, String> state,
        List<RoleHolder> roles,
        ItemLabel label,
        ScmCapturedWork work,
        ObjectNode record,
        @Nullable ObjectNode decision,
        List<Participant> participants,
        Instant observedAt,
        FoundBy foundBy) {
    public static final int SCHEMA = 1;

    /**
     * A person the record copies. Privacy selects the occasion by {@code userId} or by the native {@code identity},
     * so it still finds a person whose mirror row is gone; capture checks consent and records the copy by them.
     */
    public record Participant(long userId, PersonIdentity identity, PersonCopyIdentity copy) {
        static Participant of(User user) {
            var contributor = ScmCaptureConsent.Contributor.of(user);
            return new Participant(user.getId(), contributor.identity(), contributor.copy());
        }

        ScmCaptureConsent.Contributor contributor() {
            return new ScmCaptureConsent.Contributor(identity, copy);
        }
    }

    public ScmOccasionEvidence {
        if (schema != SCHEMA) throw new IllegalArgumentException("Unsupported SCM occasion evidence");
        if (!kind.equals(ScmItemEvents.PULL_REQUEST.value()) && !kind.equals(ScmItemEvents.PULL_REQUEST_REVIEW.value()))
            throw new IllegalArgumentException("SCM occasion evidence covers pull requests and reviews only");
        if ((itemVersion == null) != (baseVersion == null) || (itemVersion == null) != (work.base() == null))
            throw new IllegalArgumentException("SCM occasion evidence pins its version and diff base together");
        if (itemVersion != null
                && !Objects.equals(
                        ScmVersion.parse(new ItemVersion(itemVersion, baseVersion))
                                .map(ScmVersion.Parsed::head)
                                .orElse(null),
                        work.head()))
            throw new IllegalArgumentException("SCM occasion evidence does not match its version");
        if (kind.equals(ScmItemEvents.PULL_REQUEST_REVIEW.value()) == (decision == null))
            throw new IllegalArgumentException("SCM occasion evidence keeps a decision for a review only");
        state = Map.copyOf(state);
        roles = List.copyOf(roles);
        participants = List.copyOf(participants);
        record = record.deepCopy();
        decision = decision == null ? null : decision.deepCopy();
    }

    @Override
    public ObjectNode record() {
        return record.deepCopy();
    }

    @Override
    public @Nullable ObjectNode decision() {
        return decision == null ? null : decision.deepCopy();
    }

    /** Whether this record is of {@code item} at all; a record of other work is corrupt, not a version mismatch. */
    boolean describes(ItemRef item) {
        return kind.equals(item.kind().value()) && Objects.equals(item.sourceId(), sourceId);
    }

    /** Whether this is the occasion of exactly {@code item} at exactly {@code requested}. */
    boolean matches(ItemRef item, ItemVersion requested) {
        return describes(item)
                && requested.value().equals(itemVersion)
                && (requested.baseVersion() == null || requested.baseVersion().equals(baseVersion));
    }

    ItemVersion version() {
        return new ItemVersion(Objects.requireNonNull(itemVersion, "The occasion has no version"), baseVersion);
    }

    ScmCapturedOccasion captured() {
        String reviewCommit = itemVersion == null
                ? null
                : ScmVersion.parse(version())
                        .map(ScmVersion.Parsed::reviewCommit)
                        .orElse(null);
        return new ScmCapturedOccasion(
                record, decision, linkedContextVersion, reviewCommit == null || reviewCommit.equals(work.head()));
    }

    /**
     * The occasion once its diff base is known: only the version and the base change. The version must be the one the
     * retained head, title, body and linked material give with that base, so the base is all it adds.
     */
    ScmOccasionEvidence pinned(ItemVersion version) {
        var parsed = ScmVersion.parse(version).orElseThrow(() -> new IllegalArgumentException("Invalid SCM version"));
        if (itemVersion != null
                || !kind.equals(ScmItemEvents.PULL_REQUEST.value())
                || !parsed.head().equals(work.head()))
            throw new IllegalArgumentException("The version is not this occasion's");
        var expected = ScmVersion.pullRequest(parsed.base(), parsed.head(), work.title(), work.body());
        if (linkedContextVersion != null)
            expected = ScmVersion.withContext(expected, new ItemVersion(linkedContextVersion, null));
        if (!expected.equals(version)) throw new IllegalArgumentException("The version is not this occasion's");
        var withBase = new ScmCapturedWork(
                work.sourceId(),
                work.repositoryId(),
                work.number(),
                work.url(),
                work.repositoryPath(),
                work.title(),
                work.body(),
                work.head(),
                parsed.base(),
                work.sourceBranch(),
                work.targetBranch(),
                work.reviewSourceId(),
                work.reviewerUserId());
        return new ScmOccasionEvidence(
                schema,
                kind,
                sourceId,
                version.value(),
                version.baseVersion(),
                linkedContextVersion,
                integration,
                state,
                roles,
                label,
                withBase,
                record,
                decision,
                participants,
                observedAt,
                foundBy);
    }
}
