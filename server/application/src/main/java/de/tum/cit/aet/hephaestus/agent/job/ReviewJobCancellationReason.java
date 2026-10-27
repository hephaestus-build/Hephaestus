package de.tum.cit.aet.hephaestus.agent.job;

public enum ReviewJobCancellationReason {
    DRAIN_GRACEFUL,
    DRAIN_IMMEDIATE,
    BUDGET_EXHAUSTED,
    MODEL_UNAVAILABLE,
    MEMBER_AI_DECLINED,
    PERSON_DATA_ERASED,
    CANCELLED_BY_ADMIN,
    CANCELLED_BY_REQUESTER,
    CONTRACT_CHANGED,
    /** The sandbox runtime had no room for the review in time, or the sandbox quota can never hold its sandbox. */
    NO_SANDBOX_ROOM
}
