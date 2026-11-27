package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the team review journey does to the product, one method for each call. A person is the account that the call is
 * made as.
 */
interface TeamReviewDriver {

    /** What a team member reads of one version's review. An anchor names an element of the version it was made on. */
    record Read(
            long practiceVersion,
            String presentation,
            String outcome,
            String summary,
            String nextStep,
            List<Anchor> anchors) {}

    record Anchor(UUID version, String element, String quote) {}

    /**
     * Everything that any read of one version gives a person: the snapshot, its normalized form, a preview, elements,
     * quotes, anchor labels and observation texts, with the state of its evidence.
     */
    record Served(
            int status,
            @Nullable String evidence,
            @Nullable String unavailableBecause,
            List<String> content) {}

    /** Staff create a team project and answer its id. */
    UUID createTeamProject(String name);

    List<UUID> twoMembers(UUID team);

    /** Staff offer the practice to the team as a review setup. */
    UUID offer(UUID team, long practice);

    /** Submits a snapshot with the link to its live source, which is for navigation and is never fetched. */
    UUID submit(UUID member, UUID setup, Captures.Capture capture, String snapshot, URI liveLink, UUID requestKey);

    UUID requestReview(UUID member, UUID version);

    /** Runs the existing review for this request, with a candidate that cites these lines. */
    void runReview(UUID review, List<Captures.Cited> cited);

    Read readInApp(UUID member, UUID review);

    Read readInEditor(UUID member, UUID review);

    long composerRuns(UUID review);

    void leave(UUID team, UUID member);

    /** A member of another workspace. */
    UUID outsider();

    int statusFor(UUID person, UUID review);

    /** The captured snapshot of the version, as the member downloads it. */
    String capturedSnapshot(UUID member, UUID version);

    void withdraw(UUID member, UUID version);

    Served served(UUID person, UUID version);

    /** The reviews that the staff result list names. */
    List<UUID> staffList();
}
