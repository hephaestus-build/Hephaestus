package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import java.util.List;
import java.util.UUID;

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

    /** Staff create a team project and answer its id. */
    UUID createTeamProject(String name);

    List<UUID> twoMembers(UUID team);

    /** Staff offer the practice to the team as a review setup. */
    UUID offer(UUID team, long practice);

    UUID submit(UUID member, UUID setup, Captures.Capture capture, String snapshot, UUID requestKey);

    UUID requestReview(UUID member, UUID version);

    /** Runs the existing review for this request, with a candidate that cites these lines. */
    void runReview(UUID review, List<Captures.Cited> cited);

    Read readInApp(UUID member, UUID review);

    Read readInEditor(UUID member, UUID review);

    long composerRuns(UUID review);

    void leave(UUID team, UUID member);

    /** The status that a member of another workspace gets for the review. */
    int statusForAnOutsider(UUID review);

    int statusFor(UUID member, UUID review);

    /** The reviews that the staff result list names. */
    List<UUID> staffList();
}
