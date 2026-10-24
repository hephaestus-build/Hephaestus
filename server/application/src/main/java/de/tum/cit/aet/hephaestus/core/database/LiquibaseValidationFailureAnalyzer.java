package de.tum.cit.aet.hephaestus.core.database;

import liquibase.exception.ValidationFailedException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Reports a changelog that Liquibase refused to run as the startup failure, so an operator reads why the database was
 * not migrated, such as the baseline cut-point message in {@code db/master.xml}.
 *
 * <p>Logs name an exception by its class only ({@code ExceptionClassStackTracePrinter}), so without this the refusal
 * text never reaches the log. Liquibase builds this report from the changelog: precondition messages, changeset ids
 * and checksums. It quotes no row values.
 */
class LiquibaseValidationFailureAnalyzer extends AbstractFailureAnalyzer<ValidationFailedException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ValidationFailedException cause) {
        return new FailureAnalysis(
                "The database changelog failed validation, so no migration ran.\n" + cause.getMessage(),
                "Resolve each failure that the report names, then start the application again.",
                cause);
    }
}
