package de.tum.cit.aet.hephaestus.testconfig;

/**
 * A fixture that inserts a READY account export takes the owner's export fence in the insert's transaction, as the
 * export worker does: the database refuses a ready export without it ({@code account_export_published_fenced}). Bind
 * the owner's account id to the one parameter of {@link #LOCK}, or of {@link #CTE} and select from {@code fence} in the
 * insert.
 */
public final class ReadyExportFence {
    /** The fence's key is the one that {@code AccountExportRepository#lockSources} and the database rule use. */
    public static final String LOCK =
            "SELECT pg_advisory_xact_lock(hashtext('account-export-sources'), hashtext(CAST(? AS text)) & 63)";

    /** {@link #LOCK} as a common table expression, for an insert that is its own transaction. */
    public static final String CTE = "WITH fence AS MATERIALIZED (" + LOCK + " AS taken) ";

    private ReadyExportFence() {}
}
