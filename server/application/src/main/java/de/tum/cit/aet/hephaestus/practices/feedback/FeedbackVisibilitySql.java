package de.tum.cit.aet.hephaestus.practices.feedback;

/** Visibility follows the same provider object through quiet edits; the nearest explicit decision wins. */
public final class FeedbackVisibilitySql {
    private FeedbackVisibilitySql() {}
    /** Correlates to the feedback row named f. Ordinary private replacements inherit no visibility decision. */
    public static final String LATEST = """
        WITH RECURSIVE ancestors AS (
          SELECT f.id,f.replaces_id,f.destination,f.composition,0 AS depth
          UNION ALL
          SELECT prior.id,prior.replaces_id,prior.destination,prior.composition,a.depth+1
          FROM ancestors a JOIN feedback prior ON prior.workspace_id=f.workspace_id AND prior.id=a.replaces_id
          WHERE a.destination='ON_WORK' AND a.composition->>'editOf'=a.replaces_id::text)
        SELECT d.* FROM ancestors a JOIN feedback_decision d ON d.workspace_id=f.workspace_id AND d.feedback_id=a.id
        WHERE d.action IN ('WITHDRAW','RESTORE') ORDER BY a.depth,d.decided_at DESC,d.id::text DESC LIMIT 1
        """;
}
