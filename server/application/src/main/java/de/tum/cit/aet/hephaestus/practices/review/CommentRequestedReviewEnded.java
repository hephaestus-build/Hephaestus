package de.tum.cit.aet.hephaestus.practices.review;

import java.util.UUID;

/** A review that a comment on the work requested has ended, however it ended, so the comment can be answered. */
record CommentRequestedReviewEnded(UUID reviewId, long workspaceId) {}
