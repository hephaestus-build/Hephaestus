package de.tum.cit.aet.hephaestus.core.database;

import liquibase.exception.MigrationFailedException;
import liquibase.exception.PreconditionFailedException;
import liquibase.exception.ValidationFailedException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Reports why Liquibase refused to migrate the database as the startup failure, such as the baseline cut-point
 * message in {@code db/master.xml}.
 *
 * <p>Logs name an exception by its class only ({@code ExceptionClassStackTracePrinter}), so without this the refusal
 * text never reaches the log. Two refusals carry only changelog text and are reported:
 *
 * <ul>
 *   <li>A validation failure. Its report lists checksums, changeset ids and failed preconditions, but also errored
 *       preconditions, which quote the driver message. The top-level preconditions in {@code db/master.xml} must
 *       therefore read only server settings, the catalog and {@code databasechangelog}.
 *   <li>A changeset precondition that halts the update when it fails. Its message is the changeset's
 *       {@code onFailMessage} or Liquibase's expected and actual result.
 * </ul>
 *
 * <p>A changeset precondition that errors, or a failed statement, carries the driver message and is not reported.
 */
class LiquibaseRefusalFailureAnalyzer extends AbstractFailureAnalyzer<MigrationFailedException> {

    @Override
    protected @Nullable FailureAnalysis analyze(Throwable rootFailure, MigrationFailedException cause) {
        if (!(cause instanceof ValidationFailedException)
                && !(cause.getCause() instanceof PreconditionFailedException)) {
            return null;
        }
        return new FailureAnalysis(
                "Liquibase stopped the database migration.\n" + cause.getMessage(),
                "Resolve the failure that the report names, then start the application again.",
                cause);
    }
}
