package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The framework filter on {@code Practice} hides draft practices from every entity query, but native SQL bypasses it
 * (ADR 0118 decision 7). So every native statement that reads the practice table names its state, or it reads practices
 * only through rows that a draft never has. This test names each class of the second kind, with the reason.
 */
@Tag("architecture")
class DraftPracticeFilterArchTest {

    private static final Path SOURCES = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
    /** The table name in lowercase, so neither JPQL ({@code FROM Practice p}) nor prose matches; the alias, if any. */
    private static final Pattern READS_PRACTICE = Pattern.compile(
            "\\b(?i:FROM|JOIN)\\s+practice\\b(?!_)(?:\\s+(?!(?i:WHERE|ON|JOIN|LEFT|ORDER|GROUP)\\b)(\\w+))?");

    private static final Pattern CONCATENATION = Pattern.compile("\"\\s*\\+\\s*\"");
    private static final String THROUGH_RESULTS =
            "reads practices through results, and a draft has none: a trial records no result";

    private static final Map<String, String> EXEMPT = Map.of(
            "ResultQueriesImpl.java", THROUGH_RESULTS,
            "FeedbackRepository.java", THROUGH_RESULTS,
            "FeedbackQueryRepository.java", THROUGH_RESULTS,
            "DeliveredWorkFeedbackRepository.java", THROUGH_RESULTS,
            "ResearchDatasetRepository.java", THROUGH_RESULTS,
            "ProfileOverviewRepository.java", THROUGH_RESULTS,
            "ReviewLineageRepository.java", THROUGH_RESULTS,
            "ReviewQueriesImpl.java", THROUGH_RESULTS,
            "ScanMembershipQueriesImpl.java", "reads practices through scan memberships, and no scan selects a draft",
            "PracticesPrivacyRepository.java", "deletes every practice of a purged workspace, drafts too");

    @Test
    void shouldNameThePracticeStateInEveryNativeReadOfPractices() throws IOException {
        var unnamed = new TreeSet<String>();
        var exemptSeen = new TreeMap<String, Boolean>();
        try (var files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                for (String raw : Files.readString(file).split(";", -1)) {
                    String statement = CONCATENATION.matcher(raw).replaceAll("");
                    var reads = READS_PRACTICE.matcher(statement);
                    while (reads.find()) {
                        String alias = reads.group(1) == null ? "" : Pattern.quote(reads.group(1)) + "\\.";
                        if (Pattern.compile("\\b" + alias + "state\\s*=\\s*'ACTIVE'")
                                .matcher(statement)
                                .find()) continue;
                        if (EXEMPT.containsKey(name)) exemptSeen.put(name, true);
                        else unnamed.add(name);
                    }
                }
            }
        }
        assertThat(unnamed)
                .as(
                        "native SQL that reads practices must say state = 'ACTIVE', or why the drafts it cannot see never matter")
                .isEmpty();
        assertThat(exemptSeen.keySet())
                .as("an exemption whose class no longer reads practices natively must go")
                .containsExactlyInAnyOrderElementsOf(EXEMPT.keySet());
    }
}
