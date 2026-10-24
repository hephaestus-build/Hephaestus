package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import liquibase.changelog.ChangeSet;
import liquibase.exception.DatabaseException;
import liquibase.exception.MigrationFailedException;
import liquibase.exception.PreconditionErrorException;
import liquibase.exception.PreconditionFailedException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;

@Tag("unit")
class LiquibaseRefusalFailureAnalyzerTest {

    private static final ChangeSet CHANGE_SET =
            new ChangeSet("1-1", "hephaestus", false, false, "db/changelog/1_changelog.xml", null, null, null);

    @Test
    void shouldBeTheFailureAnalyzerThatSpringBootLoads() {
        // Boot's own analyzers take these constructor arguments, as at startup.
        var arguments = ArgumentResolver.of(BeanFactory.class, new DefaultListableBeanFactory())
                .and(Environment.class, new StandardEnvironment());

        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(FailureAnalyzer.class, arguments))
                .hasAtLeastOneElementOfType(LiquibaseRefusalFailureAnalyzer.class);
    }

    @Test
    void shouldReportTheMessageWhenAChangesetPreconditionHalts() {
        var halted = new MigrationFailedException(
                CHANGE_SET,
                "Upgrade v0.80.0 first.",
                new PreconditionFailedException("Upgrade v0.80.0 first.", null, null));

        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(new IllegalStateException(halted)))
                .extracting(FailureAnalysis::getDescription)
                .asString()
                .contains("Upgrade v0.80.0 first.");
    }

    @Test
    void shouldNotReportDriverTextWhenAPreconditionErrorsOrAStatementFails() {
        var errored = new MigrationFailedException(
                CHANGE_SET, "ERROR: relation \"secret\" does not exist", new PreconditionErrorException("", List.of()));
        var failed = new MigrationFailedException(CHANGE_SET, new DatabaseException("ERROR: value 'a@b.c' violates"));

        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(errored)).isNull();
        assertThat(new LiquibaseRefusalFailureAnalyzer().analyze(failed)).isNull();
    }
}
