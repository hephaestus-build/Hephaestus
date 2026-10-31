package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import liquibase.Scope;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class LiquibaseMigrationOrderTest extends BaseUnitTest {

    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("try") // Liquibase declares close() with Exception, which this test propagates.
    void shouldResolveTheSamePendingOrderAndPathsForA0950Database() throws Exception {
        String first = "20261031000100-first.xml";
        String second = "20261031000200-second.xml";
        Path migrations = directory.resolve("db/changelog/next");
        Files.createDirectories(migrations);
        // Write in reverse order: the filesystem's insertion order must not decide execution order.
        Files.writeString(migrations.resolve(second), migration("second"));
        Files.writeString(migrations.resolve(first), migration("first"));
        Files.writeString(directory.resolve("db/after.xml"), resource("db/master.xml"));
        try (var files = new DirectoryResourceAccessor(directory);
                var classpath = new SpringResourceAccessor(new DefaultResourceLoader())) {
            var resources = new CompositeResourceAccessor(files, classpath);
            String explicit = resources.search("db/changelog/next", false).stream()
                    .filter(file -> file.getPath().endsWith(".xml"))
                    .sorted(Comparator.comparing(Resource::getPath))
                    .map(file -> "<include file=\"" + file.getPath() + "\"/>")
                    .collect(Collectors.joining());
            Files.writeString(
                    directory.resolve("db/before.xml"),
                    resource("db/order/master-before-include-all.xml")
                            .replace("</databaseChangeLog>", explicit + "</databaseChangeLog>"));
            var before = changes("db/before.xml", resources);
            var after = changes("db/after.xml", resources);
            assertThat(identities(after)).isEqualTo(identities(before));
            int cut = IntStream.range(0, before.size())
                    .filter(index -> before.get(index).getId().equals("1793208610071-3"))
                    .findFirst()
                    .orElseThrow();
            assertThat(before.get(cut).getFilePath()).isEqualTo("db/changelog/1793208610071_changelog.xml");
            assertThat(identities(after.subList(cut + 1, after.size())))
                    .containsSubsequence(
                            "db/changelog/1793058726214_changelog.xml::1793058726214-1::hephaestus",
                            "db/changelog/1793394316000_changelog.xml::1793394316000-1::hephaestus",
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

    private static List<String> identities(List<ChangeSet> changes) {
        return changes.stream()
                .map(change -> change.getFilePath() + "::" + change.getId() + "::" + change.getAuthor())
                .toList();
    }

    private static String resource(String file) throws Exception {
        try (var input = Objects.requireNonNull(
                LiquibaseMigrationOrderTest.class.getClassLoader().getResourceAsStream(file))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String migration(String id) {
        return "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">"
                + "<changeSet id=\"" + id
                + "\" author=\"order-test\"><sql>SELECT 1</sql></changeSet></databaseChangeLog>";
    }
}
