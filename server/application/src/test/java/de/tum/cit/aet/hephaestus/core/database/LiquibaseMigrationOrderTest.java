package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import liquibase.Scope;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class LiquibaseMigrationOrderTest extends BaseUnitTest {

    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("try") // Liquibase declares close() with Exception, which this test propagates.
    void shouldRunTheBaselineThenNextMigrationsInFilenameOrder() throws Exception {
        String first = "99991031000100-first.xml";
        String second = "99991031000200-second.xml";
        Path migrations = directory.resolve("db/changelog/next");
        Files.createDirectories(migrations);
        // Write in reverse order: the filesystem's insertion order must not decide execution order.
        Files.writeString(migrations.resolve(second), migration("second"));
        Files.writeString(migrations.resolve(first), migration("first"));
        Files.writeString(directory.resolve("db/after.xml"), resource("db/master.xml"));
        try (var files = new DirectoryResourceAccessor(directory);
                var classpath = new SpringResourceAccessor(new DefaultResourceLoader())) {
            var resources = new CompositeResourceAccessor(files, classpath);
            List<String> identities = changes("db/after.xml", resources).stream()
                    .map(change -> change.getFilePath() + "::" + change.getId() + "::" + change.getAuthor())
                    .toList();
            assertThat(identities.subList(0, 4))
                    .containsExactly(
                            "db/changelog/1.0.0/baseline.xml::1.0.0-schema::hephaestus",
                            "db/changelog/1.0.0/baseline.xml::1.0.0-seed::hephaestus",
                            "db/changelog/1.0.0/baseline.xml::1.0.0-audit::hephaestus",
                            "db/changelog/1.0.0/baseline.xml::1.0.0-tag::hephaestus");
            assertThat(identities.subList(identities.size() - 2, identities.size()))
                    .containsExactly(
                            "db/changelog/next/" + first + "::first::order-test",
                            "db/changelog/next/" + second + "::second::order-test");
        }
    }

    private static List<ChangeSet> changes(String file, CompositeResourceAccessor resources) throws Exception {
        return Scope.child(
                Map.of(Scope.Attr.resourceAccessor.name(), resources),
                () -> ChangeLogParserFactory.getInstance()
                        .getParser(file, resources)
                        .parse(file, new ChangeLogParameters(), resources)
                        .getChangeSets());
    }

    private static String migration(String id) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="%s" author="order-test"><sql>SELECT 1</sql></changeSet>
                </databaseChangeLog>
                """.formatted(id);
    }

    private static String resource(String path) throws Exception {
        try (var input = Objects.requireNonNull(
                LiquibaseMigrationOrderTest.class.getClassLoader().getResourceAsStream(path))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
