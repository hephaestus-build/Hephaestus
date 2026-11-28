package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempt.WorkClass;
import java.time.Instant;
import java.util.UUID;

/** One claim of an attempt: the epoch and token that every later step of its holder presents. */
public record AttemptLease(
        long accountId,
        UUID attemptId,
        UUID requestId,
        int requestVersion,
        WorkClass workClass,
        long leaseEpoch,
        UUID leaseToken,
        Instant leasedUntil) {}
