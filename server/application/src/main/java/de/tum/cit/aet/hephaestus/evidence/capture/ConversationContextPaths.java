package de.tum.cit.aet.hephaestus.evidence.capture;

/** Paths shared by context producers and the conversation's fetch protocol. */
public final class ConversationContextPaths {
    public static final String MERGE_READINESS = ContentSource.OUTPUT_PREFIX + "merge_readiness.json";
    public static final String RECENT_AUTHORED_WORK = ContentSource.OUTPUT_PREFIX + "recent_authored_work.json";
    public static final String ACTIVITY = ContentSource.OUTPUT_PREFIX + "activity.json";
    public static final int MAX_CHARS = 200_000;

    private ConversationContextPaths() {}

    public static String reviewAttempts(String segment, long sourceId) {
        return ContentSource.OUTPUT_PREFIX + "review_attempts/" + segment + "/" + sourceId + ".json";
    }

    /** One authored pull request read on demand, in any stored state. */
    public static String mergeReadiness(long pullRequestId) {
        return ContentSource.OUTPUT_PREFIX + "merge_readiness/" + pullRequestId + ".json";
    }

    /** One issue the developer authored or is assigned to, read on demand. */
    public static String issue(long issueId) {
        return ContentSource.OUTPUT_PREFIX + "issue/" + issueId + ".json";
    }
}
