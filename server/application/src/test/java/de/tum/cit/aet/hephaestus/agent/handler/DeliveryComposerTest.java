package de.tum.cit.aet.hephaestus.agent.handler;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.WithheldObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DeliveryComposerTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static DeliveryContent content(@Nullable DeliveryContent content) {
        assertThat(content).isNotNull();
        return content;
    }

    private static String note(@Nullable DeliveryContent content) {
        String note = content(content).mrNote();
        assertThat(note).isNotNull();
        return note;
    }

    private JsonNode buildEvidence(@Nullable List<LocationSpec> locations, @Nullable List<String> snippets) {
        ObjectNode evidence = objectMapper.createObjectNode();
        if (locations != null && !locations.isEmpty()) {
            ArrayNode citations = evidence.putArray("citations");
            for (LocationSpec loc : locations) {
                ObjectNode citation = citations.addObject();
                citation.put("sourceKind", loc.sourceKind);
                citation.put(
                        "artifactPath",
                        loc.sourceKind.equals("scm.pull-request.diff")
                                ? "inputs/context/diff.patch"
                                : "inputs/context/metadata.json");
                citation.put("path", loc.path);
                if (loc.sourceKind.equals("scm.pull-request.diff")) {
                    citation.put("side", "NEW");
                }
                citation.put("startLine", loc.startLine);
                if (loc.endLine != null) {
                    citation.put("endLine", loc.endLine);
                }
                if (snippets != null && !snippets.isEmpty()) {
                    citation.put("quote", snippets.get(Math.min(citations.size() - 1, snippets.size() - 1)));
                }
                citation.put("quoteRedacted", false);
            }
        }
        return evidence;
    }

    private record LocationSpec(
            String path, int startLine, @Nullable Integer endLine, String sourceKind) {
        LocationSpec(String path, int startLine) {
            this(path, startLine, null, "scm.pull-request.diff");
        }

        LocationSpec(String path, int startLine, String sourceKind) {
            this(path, startLine, null, sourceKind);
        }
    }

    private ValidatedObservation positiveObservation(String slug) {
        return new ValidatedObservation(
                slug,
                humanizeTitle(slug) + " (positive)",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                null);
    }

    private ValidatedObservation negativeObservation(
            String slug,
            String title,
            Severity severity,
            @Nullable List<LocationSpec> locations,
            @Nullable List<String> snippets,
            @Nullable String reasoning) {
        return new ValidatedObservation(
                slug, title, Presence.ABSENT, Assessment.BAD, severity, buildEvidence(locations, snippets), reasoning);
    }

    private static String humanizeTitle(String slug) {
        return slug.replace('-', ' ').substring(0, 1).toUpperCase()
                + slug.replace('-', ' ').substring(1);
    }

    private List<ValidatedObservation> mixedObservations() {
        List<ValidatedObservation> observations = new ArrayList<>();

        observations.add(positiveObservation("error-state-handling"));
        observations.add(positiveObservation("view-decomposition"));
        observations.add(positiveObservation("meaningful-naming"));

        observations.add(
                negativeObservation(
                        "avoids-insecure-defaults-and-over-broad-permissions",
                        "Hardcoded API key exposed in source",
                        Severity.CRITICAL,
                        List.of(new LocationSpec("Config/APIKeys.swift", 5)),
                        List.of("let apiKey = \"sk-abc123\""),
                        "An API key is hardcoded directly in source code. Anyone with repository access can extract this secret and use it to make authenticated API calls on your behalf."));

        observations.add(
                negativeObservation(
                        "fatal-error-crash",
                        "Force-unwrap causes crash on invalid URL",
                        Severity.MAJOR,
                        List.of(new LocationSpec("Views/StockView.swift", 42)),
                        List.of("let url = URL(string: urlString)!"),
                        "Force-unwrapping URL(string:) will crash the app if urlString contains invalid characters or is malformed. This is a common cause of App Store rejections."));

        observations.add(
                negativeObservation(
                        "code-hygiene",
                        "Commented-out code left in view",
                        Severity.MINOR,
                        List.of(new LocationSpec("Views/DashboardView.swift", 15)),
                        null,
                        "Commented-out code adds noise and makes diffs harder to review. Remove dead code and rely on version control history instead."));

        observations.add(
                negativeObservation(
                        "meaningful-naming",
                        "Non-descriptive type name 'Data'",
                        Severity.MINOR,
                        List.of(new LocationSpec("Models/Data.swift", 8)),
                        null,
                        "The type name 'Data' shadows Foundation.Data and conveys no domain meaning. Rename to something descriptive like 'PortfolioSnapshot' or 'StockQuote'."));

        return observations;
    }

    @Test
    void compose_withMixedObservations_producesExpectedMrNote() {
        List<ValidatedObservation> observations = mixedObservations();

        DeliveryContent result = DeliveryComposer.compose(observations);

        assertThat(result).isNotNull();
        String mrNote = result.mrNote();
        assertThat(mrNote).isNotNull();

        assertThat(mrNote).doesNotContain("[CRITICAL]");
        assertThat(mrNote).doesNotContain("[MAJOR]");
        assertThat(mrNote).doesNotContain("[MINOR]");
        assertThat(reachedTheDeveloper(result)).contains("Hardcoded API key exposed in source");
        assertThat(reachedTheDeveloper(result)).contains("Config/APIKeys.swift:5");
        assertThat(reachedTheDeveloper(result)).contains("Force-unwrap causes crash on invalid URL");
        assertThat(reachedTheDeveloper(result)).contains("Views/StockView.swift:42");
        assertThat(reachedTheDeveloper(result)).contains("Commented-out code left in view");
        assertThat(reachedTheDeveloper(result)).contains("Views/DashboardView.swift:15");
        assertThat(reachedTheDeveloper(result)).contains("Non-descriptive type name 'Data'");
        assertThat(reachedTheDeveloper(result)).contains("Models/Data.swift:8");

        assertThat(mrNote).doesNotContain("You wrote:");
        assertThat(mrNote).doesNotContain("ProcessInfo.processInfo.environment");
        assertThat(mrNote).doesNotContain("guard let url");
        assertThat(mrNote).doesNotContain("hardcoded directly in source code");
    }

    @Test
    void shouldUseLeadAsWholeOpeningWhenPresent() {
        List<ValidatedObservation> observations = List.of(
                positiveObservation("scope-one-reviewable-change"),
                negativeObservation(
                        "describe-what-and-why",
                        "PR description lacks a rationale sentence",
                        Severity.MINOR,
                        List.of(),
                        List.of(),
                        "The body lists what changed but not why."));
        String lead = "You kept this to one concern, but the description never says why it changed.";

        String withLead = note(
                DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST, Map.of(), null, List.of(), lead));
        String withoutLead = note(
                DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST, Map.of(), null, List.of(), null));

        assertThat(withLead).isEqualTo(lead + "\n\n" + withoutLead);
        assertThat(withoutLead)
                .as("no opening of the server's own")
                .startsWith("PR description lacks a rationale sentence");
    }

    @Test
    void shouldNeverRenderPracticeCatalogueWording() {
        String why = "Code without a test is a promise nobody can check, and it is one careless refactor away.";

        DeliveryContent result = DeliveryComposer.compose(
                List.of(negativeObservation(
                        "ships-tests-with-the-change",
                        "The discount fix has no test",
                        Severity.MAJOR,
                        List.of(new LocationSpec("Checkout/CartTotals.swift", 39)),
                        List.of("return subtotal"),
                        "The changed branch is not exercised by anything in this diff.")),
                ArtifactKinds.PULL_REQUEST,
                Map.of("ships-tests-with-the-change", why));

        assertThat(result).isNotNull();
        String everything = result.mrNote()
                + result.diffNotes().stream().map(DiffNote::body).collect(joining());
        assertThat(everything)
                .as("the workspace's wording about the practice is not a sentence about this change")
                .doesNotContain("Why this matters")
                .doesNotContain("promise nobody can check");
        assertThat(everything).contains("The discount fix has no test");

        DeliveryContent clean = DeliveryComposer.compose(
                List.of(positiveObservation("ships-tests-with-the-change")),
                ArtifactKinds.PULL_REQUEST,
                Map.of("ships-tests-with-the-change", why));
        assertThat(clean).isNotNull();
        assertThat(clean.mrNote())
                .as("the rule holds on a clean change too")
                .doesNotContain("Why this matters")
                .doesNotContain("promise nobody can check");
    }

    @Test
    void shouldOmitLeadWhenSanitizedEmpty() {
        List<ValidatedObservation> observations = List.of(negativeObservation(
                "describe-what-and-why",
                "PR description lacks a rationale sentence",
                Severity.MINOR,
                List.of(),
                List.of(),
                "The body lists what changed but not why."));

        String mrNote = note(DeliveryComposer.compose(
                observations,
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(),
                "NEGATIVE observation, MINOR severity."));

        assertThat(mrNote).startsWith("PR description lacks a rationale sentence");
    }

    private static final List<ValidatedObservation> ONE_MINOR = List.of(new ValidatedObservation(
            "describe-what-and-why",
            "PR description lacks a rationale sentence",
            Presence.ABSENT,
            Assessment.BAD,
            Severity.MINOR,
            null,
            "The body lists what changed but not why."));

    private static String noteWithLead(List<ValidatedObservation> observations, String lead) {
        return note(
                DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST, Map.of(), null, List.of(), lead));
    }

    @Test
    void shouldKeepCompleteSentencesWhenLeadExceedsBudget() {
        String sentence = "This change moves the retry loop out of the poller and into its own class. ";
        String mrNote = noteWithLead(ONE_MINOR, sentence.repeat(6));

        String opening = mrNote.substring(0, mrNote.indexOf("PR description lacks a rationale sentence"));
        assertThat(opening.strip())
                .hasSizeLessThanOrEqualTo(240)
                .endsWith("own class.")
                .startsWith(sentence.strip());
    }

    @Test
    void shouldOrderCriticalFindingBeforeMinorFindingWhenBothAreInSummary() {
        var critical = negativeObservation(
                "avoids-insecure-defaults-and-over-broad-permissions",
                "Token committed to the repo",
                Severity.CRITICAL,
                List.of(),
                List.of("token: abc123"),
                "The token is written into checked-in config.");
        var minor = negativeObservation(
                "describe-what-and-why",
                "The description says what changed but not why",
                Severity.MINOR,
                List.of(),
                List.of(),
                "The body lists files and no reason.");

        String mrNote = note(DeliveryComposer.compose(List.of(minor, critical), ArtifactKinds.PULL_REQUEST));

        assertThat(mrNote).contains("Token committed to the repo", "The description says what changed but not why");
        assertThat(mrNote.indexOf("Token committed to the repo"))
                .as("a reader who stops after the first block should have met the worst of it")
                .isLessThan(mrNote.indexOf("The description says what changed but not why"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "The retry loop never breaks:\n```java\nwhile (true) {",
                "Two clean commits. ~~~",
                "Nice split. <!-- hidden",
                "See [the guide](https://example.com/x) for the pattern.",
                "Good split, and this is ready to merge.",
                "LGTM on the structure; one thing below.",
                "Solid change. I approve the new boundary.",
                "This can merge.",
                "- Retry handling",
                "1. Retry handling",
                "# Retry handling",
                "| one | two |",
            })
    void shouldDropLeadWhenItContainsMarkupLinkOrMergeVerdict(String lead) {
        assertThat(noteWithLead(ONE_MINOR, lead))
                .as("the opening carries no markup, no link and no verdict, so a lead with one opens nothing")
                .startsWith("PR description lacks a rationale sentence");
    }

    @Test
    void shouldUseLeadForCleanChange() {
        ValidatedObservation strength = new ValidatedObservation(
                "error-state-handling",
                "Error state handling (positive)",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                "Network errors are surfaced to the user via an alert.");
        String lead = "Small change, and it carries its own error handling.";

        assertThat(noteWithLead(List.of(strength), lead)).startsWith(lead + "\n\n");
    }

    @Test
    void compose_withAllPositive_producesObservationNoteWithoutPraise() {
        List<ValidatedObservation> observations = List.of(
                positiveObservation("error-state-handling"),
                positiveObservation("view-decomposition"),
                positiveObservation("meaningful-naming"));

        DeliveryContent result = DeliveryComposer.compose(observations);

        assertThat(result).isNotNull();
        // Each observation says what it observed, so each earns a bullet; none of it is praise.
        assertThat(result.mrNote()).contains("Error state handling").contains("View decomposition");
        assertThat(result.mrNote()).doesNotContain("stood out");
        assertThat(result.diffNotes()).isEmpty();
    }

    @Test
    void compose_withAllPositive_bulletsSayWhatWasObservedNotHowItWasChecked() {
        ValidatedObservation observed = new ValidatedObservation(
                "error-state-handling",
                "Network errors are surfaced to the user via an alert",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                // The warrant is written for whoever audits the review: first person, about the search.
                "I walked every added subscribe block and followed each error callback to its sink.");

        DeliveryContent result = DeliveryComposer.compose(List.of(observed));

        assertThat(result).isNotNull();
        assertThat(result.mrNote())
                .contains("What's working well here")
                .contains("Error state handling")
                .contains("Network errors are surfaced")
                .doesNotContain("I walked")
                .doesNotContain("No issues found");
        assertThat(result.diffNotes()).isEmpty();
    }

    @Test
    void compose_withManyNegatives_allInCompactList() {
        List<ValidatedObservation> observations = new ArrayList<>();

        observations.add(negativeObservation(
                "avoids-insecure-defaults-and-over-broad-permissions",
                "Hardcoded secret",
                Severity.CRITICAL,
                List.of(new LocationSpec("Config/Keys.swift", 1)),
                List.of("let key = \"secret\""),
                "Secret exposed."));
        observations.add(negativeObservation(
                "fatal-error-crash",
                "Force unwrap crash",
                Severity.MAJOR,
                List.of(new LocationSpec("Views/A.swift", 10)),
                List.of("url!"),
                "Crash risk."));
        observations.add(negativeObservation(
                "code-hygiene",
                "Dead code",
                Severity.MINOR,
                List.of(new LocationSpec("Views/B.swift", 20)),
                null,
                "Remove dead code."));
        observations.add(negativeObservation(
                "meaningful-naming",
                "Bad name",
                Severity.MINOR,
                List.of(new LocationSpec("Models/C.swift", 30)),
                null,
                "Use descriptive names."));
        observations.add(negativeObservation(
                "error-state-handling",
                "Missing error UI",
                Severity.MINOR,
                List.of(new LocationSpec("Views/D.swift", 40)),
                null,
                "Show errors to user."));
        observations.add(negativeObservation(
                "view-decomposition",
                "Monolith view",
                Severity.MINOR,
                List.of(new LocationSpec("Views/E.swift", 50)),
                null,
                "Break view into subviews."));
        observations.add(negativeObservation(
                "accessibility",
                "Missing labels",
                Severity.INFO,
                List.of(new LocationSpec("Views/F.swift", 60)),
                null,
                "Add accessibility labels."));

        DeliveryContent result = DeliveryComposer.compose(observations);

        assertThat(result).isNotNull();
        String mrNote = result.mrNote();
        assertThat(mrNote).isNotNull();

        String everything = reachedTheDeveloper(result);
        int criticalIdx = everything.indexOf("\uD83D\uDD34");
        int majorIdx = everything.indexOf("\uD83D\uDFE0");
        assertThat(criticalIdx).isGreaterThanOrEqualTo(0);
        assertThat(majorIdx).isGreaterThan(criticalIdx);

        assertThat(result.diffNotes()).hasSize(5);
        assertThat(mrNote).doesNotContain("Missing labels");
    }

    @Test
    void compose_diffNotes_allNegativesGetInlineComments() {
        List<ValidatedObservation> observations = mixedObservations();

        DeliveryContent result = DeliveryComposer.compose(observations);

        assertThat(result).isNotNull();
        List<DiffNote> diffNotes = result.diffNotes();

        assertThat(diffNotes).hasSize(4);

        DiffNote secretsNote = diffNotes.stream()
                .filter(n -> n.filePath().equals("Config/APIKeys.swift"))
                .findFirst()
                .orElseThrow();
        assertThat(secretsNote.startLine()).isEqualTo(5);
        assertThat(secretsNote.body()).contains("An API key is hardcoded directly in source code");

        DiffNote crashNote = diffNotes.stream()
                .filter(n -> n.filePath().equals("Views/StockView.swift"))
                .findFirst()
                .orElseThrow();
        assertThat(crashNote.startLine()).isEqualTo(42);
        assertThat(crashNote.body()).contains("will crash the app if urlString contains invalid characters");

        DiffNote hygieneNote = diffNotes.stream()
                .filter(n -> n.filePath().equals("Views/DashboardView.swift"))
                .findFirst()
                .orElseThrow();
        assertThat(hygieneNote.startLine()).isEqualTo(15);

        DiffNote namingNote = diffNotes.stream()
                .filter(n -> n.filePath().equals("Models/Data.swift"))
                .findFirst()
                .orElseThrow();
        assertThat(namingNote.startLine()).isEqualTo(8);

        for (DiffNote dn : diffNotes) {
            assertThat(dn.body()).startsWith("**");
        }
    }

    @Test
    void compose_withNull_returnsNull() {
        assertThat(DeliveryComposer.compose(null)).isNull();
    }

    @Test
    void compose_withEmptyList_returnsNull() {
        assertThat(DeliveryComposer.compose(List.of())).isNull();
    }

    @Test
    void compose_nonInlinableObservations_renderedInFullInMrNote() {
        List<ValidatedObservation> observations = List.of(
                negativeObservation(
                        "mr-description-quality",
                        "MR description is empty",
                        Severity.MAJOR,
                        null,
                        null,
                        "The MR description is empty, making it hard for reviewers to understand the changes."),
                negativeObservation(
                        "code-hygiene",
                        "Unused import",
                        Severity.MINOR,
                        List.of(new LocationSpec("src/components/Button.tsx", 1)),
                        List.of("import React from 'react';"),
                        "Remove unused imports."));

        DeliveryContent result = DeliveryComposer.compose(observations);
        assertThat(result).isNotNull();
        String mrNote = result.mrNote();

        assertThat(reachedTheDeveloper(result)).contains("MR description is empty");
        assertThat(reachedTheDeveloper(result)).contains("hard for reviewers to understand the changes");

        assertThat(reachedTheDeveloper(result)).contains("Unused import");
        assertThat(reachedTheDeveloper(result)).contains("src/components/Button.tsx:1");
        assertThat(mrNote).doesNotContain("Remove unused imports.");

        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).filePath()).isEqualTo("src/components/Button.tsx");
    }

    @Test
    void compose_minorObservations_reasoningInDiffNote() {
        List<ValidatedObservation> observations = List.of(negativeObservation(
                "code-hygiene",
                "Dead code in view",
                Severity.MINOR,
                List.of(new LocationSpec("Views/DashboardView.swift", 15)),
                null,
                "Commented-out code adds noise."));

        DeliveryContent result = DeliveryComposer.compose(observations);
        assertThat(result).isNotNull();

        String mrNote = result.mrNote();
        assertThat(reachedTheDeveloper(result)).contains("Dead code in view");
        assertThat(reachedTheDeveloper(result)).contains("Views/DashboardView.swift:15");

        assertThat(result.diffNotes()).hasSize(1);
        DiffNote note = result.diffNotes().get(0);
        assertThat(note.body()).contains("Commented-out code adds noise.");
    }

    @Test
    void sanitizeStudentText_stripsInternalGradingVocabulary() {
        String leaked1 = "The body has no rationale, which results in a NEGATIVE observation with MINOR severity.";
        String leaked2 =
                "This exceeds the ≤200 line threshold for a POSITIVE observation, placing it in the INFO severity band.";
        String leaked3 = "The title is generic, violating the practice that requires an imperative summary.";

        String leaked4 = "The title is descriptive but the body only lists what was done without a quoted sentence "
                + "that explains why. The practice requires a specific 'why' sentence to be present for a "
                + "POSITIVE observation; its absence leads to this point at the MINOR severity level.";

        for (String s : List.of(leaked1, leaked2, leaked3, leaked4)) {
            String clean = DeliveryComposer.sanitizeStudentText(s);
            assertThat(clean).doesNotContainIgnoringCase("NEGATIVE observation");
            assertThat(clean).doesNotContainIgnoringCase("POSITIVE observation");
            assertThat(clean).doesNotContainIgnoringCase("POSITIVE observation");
            assertThat(clean).doesNotContainIgnoringCase("severity band");
            assertThat(clean).doesNotContainIgnoringCase("severity level");
            assertThat(clean).doesNotContainIgnoringCase("MINOR severity");
            assertThat(clean).doesNotContainIgnoringCase("the practice requires");
            assertThat(clean).doesNotContainIgnoringCase("line threshold");
        }
        assertThat(DeliveryComposer.sanitizeStudentText(leaked4)).contains("only lists what was done");

        String banded =
                "Your change is fine. The 201-400 range is the acceptable upper band, so this lands in the INFO bucket.";
        String cleanBanded = DeliveryComposer.sanitizeStudentText(banded);
        assertThat(cleanBanded).doesNotContainIgnoringCase("upper band");
        assertThat(cleanBanded).doesNotContainIgnoringCase("INFO bucket");
        assertThat(cleanBanded).contains("Your change is fine.");

        String secretGuidance = "Move the hardcoded secret/credential out of source into the environment.";
        assertThat(DeliveryComposer.sanitizeStudentText(secretGuidance)).isEqualTo(secretGuidance);
    }

    @Test
    void sanitizeStudentText_stripsRubricComputationLeaks() {
        String leak = "This change is large. enriched=true. Metadata: A=4094, D=326, A+D=4420, F=28. "
                + "Raw bucket: 4420 > 800 -> MAJOR; also 28 > 20 -> MAJOR. Generated/vendored check: none. "
                + "DEFECT-DETECTOR: only NEGATIVE or NOT_APPLICABLE. Consider splitting the change.";
        String clean = DeliveryComposer.sanitizeStudentText(leak);
        assertThat(clean).doesNotContain("Raw bucket");
        assertThat(clean).doesNotContain("-> MAJOR");
        assertThat(clean).doesNotContain("A+D=4420");
        assertThat(clean).doesNotContain("DEFECT-DETECTOR");
        assertThat(clean).doesNotContain("enriched=true");
        assertThat(clean).doesNotContain("Generated/vendored check");
        assertThat(clean).contains("This change is large.");
        assertThat(clean).contains("Consider splitting the change.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gptOssScoringMachineryLeaks")
    void sanitizeStudentText_stripsGptOssScoringMachineryLeak(
            String leakClass, String input, List<String> mustDrop, String mustKeep) {
        String clean = DeliveryComposer.sanitizeStudentText(input);
        assertThat(clean).doesNotContain(mustDrop.toArray(String[]::new));
        if (mustKeep != null) {
            assertThat(clean).contains(mustKeep);
        }
    }

    static Stream<Arguments> gptOssScoringMachineryLeaks() {
        return Stream.of(
                Arguments.of(
                        "noise-fraction + band word",
                        "Two of the fourteen commit subjects are generic. The noise fraction (2/14 ≈ 0.14) is ≤ 0.25, so the severity is INFO. Prefer specific, imperative commit subjects.",
                        List.of("noise fraction", "≤ 0.25", "severity is INFO"),
                        "Prefer specific, imperative commit subjects."),
                Arguments.of(
                        "raw draft/WIP field names",
                        "The PR is marked as ready (is_draft false, no WIP token), satisfying the traceability requirement.",
                        List.of("is_draft", "WIP token", "satisfying the traceability requirement"),
                        null),
                Arguments.of(
                        "the-practice-flags meta-voice",
                        "Debug prints were left in the code. The practice flags such leftover scaffolding as a blemish. Remove them before merging.",
                        List.of("The practice flags"),
                        "Remove them before merging."),
                Arguments.of(
                        "parenthesised scoring counters",
                        "Metadata lists 13 non-merge commits (T = 13). Three commit subjects combine distinct concerns with \"and\", giving K = 3. Separate each logical change into its own commit.",
                        List.of("T = 13", "K = 3"),
                        "Separate each logical change into its own commit."),
                Arguments.of(
                        "adr-0022 presence/assessment 'is' phrasing",
                        "The PR body omits any rationale. The presence is ABSENT so the assessment is BAD. Add a short Why section.",
                        List.of("presence is ABSENT", "assessment is BAD"),
                        "Add a short Why section."),
                Arguments.of(
                        "adr-0022 presence/assessment tuple",
                        "The error path is swallowed silently. This lands as (PRESENT, BAD) in the rubric. Rethrow or log the failure so it is visible.",
                        List.of("(PRESENT, BAD)"),
                        "Rethrow or log the failure so it is visible."),
                Arguments.of(
                        "adr-0022 assessment band-routing arrow",
                        "No tests accompany the change. presence ABSENT -> BAD per the criteria. Add a test that exercises the new branch.",
                        List.of("-> BAD"),
                        "Add a test that exercises the new branch."));
    }

    @Test
    void sanitizeStudentText_stripsCrossPracticeOrchestrationLeaks() {
        String leak = "test_presence.json reports zero test files. This is the sole owner (cross-practice) of this "
                + "lesson: ready-and-traceable-handoff suppressed its DoD-honesty contradiction, and "
                + "ships-tests-with-the-change emitted NOT_APPLICABLE, both deferring here. With zero test files, "
                + "any Definition-of-Done claim that all tests pass is vacuous. This is a team-wide standing nudge, "
                + "never a per-MR blocker.";
        String clean = DeliveryComposer.sanitizeStudentText(leak);
        assertThat(clean).doesNotContain("cross-practice");
        assertThat(clean).doesNotContain("sole owner");
        assertThat(clean).doesNotContain("deferring here");
        assertThat(clean).doesNotContain("suppressed its");
        assertThat(clean).doesNotContain("emitted NOT_APPLICABLE");
        assertThat(clean).doesNotContain("standing nudge");
        assertThat(clean).doesNotContain("per-MR blocker");
        assertThat(clean).contains("zero test files");
        assertThat(clean).contains("Definition-of-Done claim that all tests pass is vacuous");
    }

    @Test
    void sanitizeStudentText_stripsObservationJustificationLeaks() {
        String leak = "The body describes what changed but omits the why. Since the change touches only one file, the "
                + "combined observation is NOT_OBSERVED at MAJOR. Per the umbrella calibration this is MINOR "
                + "(a decomposition nudge), not MAJOR. Even a fully absent rationale would be capped at MINOR here. "
                + "No sentence uses a reason connective such as 'so that', 'because', or 'to avoid'. Add a short "
                + "Why section that states the problem this change solves.";
        String clean = DeliveryComposer.sanitizeStudentText(leak);
        assertThat(clean).doesNotContain("observation is NOT_OBSERVED");
        assertThat(clean).doesNotContain("umbrella calibration");
        assertThat(clean).doesNotContain("not MAJOR");
        assertThat(clean).doesNotContain("capped at MINOR");
        assertThat(clean).doesNotContain("reason connective");
        assertThat(clean).contains("describes what changed but omits the why");
        assertThat(clean).contains("Add a short Why section");
    }

    @Test
    void sanitizeStudentText_preservesMarkdownListAndHeadingNewlines() {
        String guidance = "Add an acceptance-criteria section, for example:\n\n" + "### Acceptance Criteria\n"
                + "- The workspace lists all capture sessions.\n"
                + "- Users can create, rename, and delete sessions.\n"
                + "- Sessions can be searched and filtered.\n\n"
                + "These criteria give a clear definition of done.";
        String clean = DeliveryComposer.sanitizeStudentText(guidance);
        assertThat(clean).contains("\n- The workspace lists all capture sessions.");
        assertThat(clean).contains("\n- Users can create, rename, and delete sessions.");
        assertThat(clean).contains("\n- Sessions can be searched and filtered.");
        assertThat(clean).contains("\n### Acceptance Criteria");
        assertThat(clean).doesNotContain("sessions. - Users");
        assertThat(clean).doesNotContain("\n\n\n");
    }

    @Test
    void sanitizeStudentText_repairsLeakedJsonEnvelopeCorruption() {
        String corrupt = "Add a single sentence under ## Description that states the motivation — for example: "
                + "\"## Why\nAdd a sentence: the problem this change solves, e.g. 'so the user knows to adjust "
                + "camera position for optimal scan quality'\"ws to adjust camera position for optimal scan quality'\"}\"";
        String clean = DeliveryComposer.sanitizeStudentText(corrupt);
        assertThat(clean).doesNotContain("}\"");
        assertThat(clean).doesNotContain("'\"ws");
        assertThat(clean).doesNotContain("scan quality'\"ws to adjust camera position for optimal scan quality");
        assertThat(clean).startsWith("Add a single sentence under ## Description that states the motivation");
        assertThat(clean).contains("so the user knows to adjust camera position for optimal scan quality");
    }

    @Test
    void sanitizeStudentText_leavesLegitimateBraceAndRepeatedPhraseGuidanceUntouched() {
        String jsonExample = "Pin the dependency, e.g. add a lockfile entry: {\"fastlane\": \"2.235.0\"}";
        assertThat(DeliveryComposer.sanitizeStudentText(jsonExample)).isEqualTo(jsonExample);
        String repeatedPhrase =
                "Add a Definition of Done section so the work is verifiable; the Definition of Done lists the "
                        + "checkable outcomes.";
        assertThat(DeliveryComposer.sanitizeStudentText(repeatedPhrase)).isEqualTo(repeatedPhrase);
    }

    @Test
    void compose_forIssue_negativeObservationsExpandedInFull_neverDemotedToVanishingDiffNote() {
        ValidatedObservation f = negativeObservation(
                "issue-has-checkable-outcome",
                "Missing checkable outcome",
                Severity.MINOR,
                List.of(new LocationSpec("metadata.json", 2, "scm.issue.core")),
                null,
                "The issue does not state any acceptance criteria a maintainer could verify against.");

        DeliveryContent issue = DeliveryComposer.compose(List.of(f), ArtifactKinds.ISSUE);

        assertThat(issue).isNotNull();
        assertThat(issue.mrNote()).contains("acceptance criteria a maintainer could verify against");
        assertThat(issue.diffNotes()).isEmpty();
        assertThat(issue.mrNote()).doesNotContain("metadata.json");
    }

    @Test
    void compose_noIssuesNote_skipsObservationWhoseReasoningScrubsToBlank() {
        ValidatedObservation scrubbed = new ValidatedObservation(
                "issue-has-checkable-outcome",
                // Grading vocabulary rather than an observation: scrubbed to nothing for a developer.
                "The practice requires a checkable outcome for a POSITIVE observation",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                "The practice requires a checkable outcome for a POSITIVE observation.");
        ValidatedObservation real = new ValidatedObservation(
                "issue-scoped-to-single-concern",
                "The issue describes one deliverable and stays within that single concern",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                "Its body names one outcome and the diff touches only that area.");

        DeliveryContent dc = DeliveryComposer.compose(List.of(scrubbed, real), ArtifactKinds.ISSUE);

        assertThat(dc).isNotNull();
        assertThat(dc.mrNote()).contains("one deliverable");
        assertThat(dc.mrNote()).doesNotContain(":** \n");
        assertThat(dc.mrNote()).doesNotContain("Checkable outcome:**\n");
    }

    @Test
    void compose_noIssuesNote_allReasoningScrubbed_fallsBackToNothingToChange() {
        ValidatedObservation scrubbed = new ValidatedObservation(
                "issue-has-checkable-outcome",
                // Grading vocabulary rather than an observation: scrubbed to nothing for a developer.
                "The practice requires a checkable outcome for a POSITIVE observation",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                "The practice requires a checkable outcome for a POSITIVE observation.");

        DeliveryContent dc = DeliveryComposer.compose(List.of(scrubbed), ArtifactKinds.ISSUE);

        assertThat(dc).isNotNull();
        assertThat(dc.mrNote()).contains("nothing to change here");
        assertThat(dc.mrNote()).doesNotContain("What I observed");
    }

    @Test
    void compose_allObservationsNotApplicable_returnsNullNoSpuriousAllClear() {
        ValidatedObservation na1 = new ValidatedObservation(
                "issue-scoped-to-single-concern", "n/a", Presence.NOT_APPLICABLE, null, Severity.INFO, null, "");
        ValidatedObservation na2 = new ValidatedObservation(
                "issue-has-checkable-outcome", "n/a", Presence.NOT_APPLICABLE, null, Severity.INFO, null, "");

        assertThat(DeliveryComposer.compose(List.of(na1, na2), ArtifactKinds.ISSUE))
                .isNull();
        assertThat(DeliveryComposer.compose(List.of(na1, na2), ArtifactKinds.PULL_REQUEST))
                .isNull();
    }

    @Test
    void compose_metadataObservation_dropsYouWroteEchoEntirely() {
        ValidatedObservation f = negativeObservation(
                "mr-description-quality",
                "PR description lacks clear motivation",
                Severity.MAJOR,
                List.of(new LocationSpec("metadata.json", 2, "scm.pull-request.core")),
                List.of(
                        "#39: use Logger and package\", \"body\" : \"#39: use Logger and package ## Description - use logger"),
                "The body does not explain why the change is needed.");

        DeliveryContent dc = DeliveryComposer.compose(List.of(f), ArtifactKinds.PULL_REQUEST);

        assertThat(dc).isNotNull();
        assertThat(dc.mrNote()).doesNotContain("You wrote:");
        assertThat(dc.mrNote()).doesNotContain("\"body\"");
        assertThat(dc.mrNote()).doesNotContain("\" : \"");
        assertThat(dc.mrNote()).doesNotContain("#39: use Logger");
        assertThat(dc.mrNote()).contains("does not explain why the change is needed");
    }

    @Test
    void compose_repositoryMetadataFileKeepsCodeLocation() {
        ValidatedObservation observation = negativeObservation(
                "code-hygiene",
                "Repository metadata needs validation",
                Severity.MINOR,
                List.of(new LocationSpec("metadata.json", 7)),
                List.of("\"enabled\": true"),
                "The repository configuration is not validated.");

        DeliveryContent result = DeliveryComposer.compose(List.of(observation), ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).singleElement().satisfies(note -> {
            assertThat(note.filePath()).isEqualTo("metadata.json");
            assertThat(note.startLine()).isEqualTo(7);
        });
    }

    @Test
    void compose_stripsLeadingRepoPrefixFromStudentLocation() {
        var observations = List.of(negativeObservation(
                "ships-tests-with-the-change",
                "Production logic without a test",
                Severity.MINOR,
                List.of(new LocationSpec("inputs/sources/scm/repo/client/App/Services/APIClient.swift", 12)),
                null,
                "New logic added without a test."));
        var dc = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);
        assertThat(dc).isNotNull();
        assertThat(reachedTheDeveloper(dc)).contains("client/App/Services/APIClient.swift");
        assertThat(reachedTheDeveloper(dc)).doesNotContain("inputs/sources/scm/repo/client/");
    }

    @Test
    void compose_epicIssue_collapsesOverlappingStructureObservations() {
        var observations = List.of(
                negativeObservation(
                        "issue-scoped-to-single-concern",
                        "Bundles concerns",
                        Severity.MAJOR,
                        null,
                        null,
                        "This epic mixes capture and export concerns."),
                negativeObservation(
                        "issue-has-checkable-outcome",
                        "No checkable outcome",
                        Severity.MINOR,
                        null,
                        null,
                        "No acceptance criteria are stated."),
                negativeObservation(
                        "breaks-large-work-into-trackable-subtasks",
                        "No subtasks",
                        Severity.MINOR,
                        null,
                        null,
                        "No subtask checklist exists."),
                negativeObservation(
                        "issue-states-an-actionable-problem",
                        "Missing beneficiary",
                        Severity.MINOR,
                        null,
                        null,
                        "No who/why is stated."));
        var dc = DeliveryComposer.compose(observations, ArtifactKinds.ISSUE);
        assertThat(dc).isNotNull();
        assertThat(dc.mrNote()).contains("mixes capture and export"); // scoped lead kept
        assertThat(dc.mrNote()).contains("No who/why is stated"); // states-actionable: distinct, survives
        assertThat(dc.mrNote())
                .as("breaks-large-work is a distinct lesson and must NEVER be deduped away (G3)")
                .contains("No subtask checklist exists");
        assertThat(dc.mrNote())
                .as("the genuine near-duplicate sibling (checkable) is collapsed into the scoped lead")
                .doesNotContain("No acceptance criteria are stated");
    }

    @Test
    void compose_pullRequest_doesNotDedupStructureObservations() {
        var observations = List.of(
                negativeObservation(
                        "issue-has-checkable-outcome",
                        "x",
                        Severity.MINOR,
                        List.of(new LocationSpec("a.swift", 1)),
                        null,
                        "No acceptance criteria are stated."),
                negativeObservation(
                        "breaks-large-work-into-trackable-subtasks",
                        "y",
                        Severity.MINOR,
                        List.of(new LocationSpec("b.swift", 1)),
                        null,
                        "No subtask checklist exists."));
        var dc = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);
        assertThat(dc).isNotNull();
        assertThat(dc.mrNote()).isNotNull();
        assertThat(dc.diffNotes()).hasSize(2);
        assertThat(dc.diffNotes())
                .extracting(PracticeDetectionResultParser.DiffNote::filePath)
                .containsExactlyInAnyOrder("a.swift", "b.swift");
    }

    @Test
    void compose_coOccurringNoTestsFact_deliveredOnceNotAsTwoMajors() {
        var observations = List.of(
                negativeObservation(
                        "ready-and-traceable-handoff",
                        "Definition of Done claims all tests pass",
                        Severity.MAJOR,
                        List.of(new LocationSpec("README.md", 3)),
                        null,
                        "The DoD checklist ticks 'all tests pass' but no test files changed."),
                negativeObservation(
                        "ships-tests-with-the-change",
                        "Production logic ships without a test",
                        Severity.MAJOR,
                        List.of(new LocationSpec("Sources/Calc.swift", 12)),
                        null,
                        "New logic added with no accompanying test."));

        var dc = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(dc).isNotNull();
        assertThat(dc.mrNote() + dc.diffNotes().get(0).body()).contains("Production logic ships without a test");
        assertThat(dc.mrNote()).doesNotContain("Definition of Done claims all tests pass");
        assertThat(dc.diffNotes()).hasSize(1);
    }

    @Test
    void compose_coOccurrencePair_keepsHandoffWhenShipsTestsAbsent() {
        var observations = List.of(negativeObservation(
                "ready-and-traceable-handoff",
                "Definition of Done claims all tests pass",
                Severity.MAJOR,
                List.of(new LocationSpec("README.md", 3)),
                null,
                "The DoD checklist ticks 'all tests pass' but no test files changed."));

        var dc = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(dc).isNotNull();
        assertThat(dc.mrNote() + dc.diffNotes().get(0).body()).contains("Definition of Done claims all tests pass");
    }

    /**
     * Everything the developer meets, on either surface. A finding with a line lives in its note on the
     * diff and a finding without one lives in the comment, and which of the two carries it is not what
     * these tests are about.
     */
    private static String reachedTheDeveloper(DeliveryContent delivery) {
        return (delivery.mrNote()
                + delivery.diffNotes().stream()
                        .map(note -> note.filePath() + ":" + note.startLine() + "\n" + note.body())
                        .collect(joining("\n")));
    }

    private ValidatedObservation negativeObservation(String slug, String title, Severity severity) {
        return new ValidatedObservation(
                slug,
                title,
                Presence.ABSENT,
                Assessment.BAD,
                severity,
                buildEvidence(List.of(new LocationSpec(slug + ".swift", 10)), null),
                title + " reasoning.");
    }

    @Test
    void compose_capsMinorTail_keepsThreeAndDisclosesOverflowHonestly() {
        List<ValidatedObservation> observations = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            observations.add(negativeObservation("nudge-" + i, "Minor nudge " + i, Severity.MINOR));
        }

        DeliveryContent result = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        String mrNote = note(result);
        assertThat(mrNote.indexOf("3 more minor suggestions are not shown"))
                .as("the disclosure describes the list, so it follows it")
                .isGreaterThan(mrNote.indexOf("Minor nudge 3"));
        assertThat(result.diffNotes()).hasSize(3);
    }

    @Test
    void compose_neverCapsBlocking_evenWithManyBlockers() {
        List<ValidatedObservation> observations = new ArrayList<>();
        observations.add(negativeObservation("sec-1", "Secret 1", Severity.CRITICAL));
        observations.add(negativeObservation("sec-2", "Secret 2", Severity.CRITICAL));
        observations.add(negativeObservation("crash-1", "Crash 1", Severity.MAJOR));
        observations.add(negativeObservation("crash-2", "Crash 2", Severity.MAJOR));
        observations.add(negativeObservation("crash-3", "Crash 3", Severity.MAJOR));
        for (int i = 1; i <= 4; i++) {
            observations.add(negativeObservation("minor-" + i, "Minor " + i, Severity.MINOR));
        }

        DeliveryContent result = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        String mrNote = result.mrNote();
        assertThat(reachedTheDeveloper(result))
                .as("every blocker survives the cap")
                .contains("Secret 1", "Secret 2", "Crash 1", "Crash 2", "Crash 3");
        assertThat(reachedTheDeveloper(result)).contains("1 more minor suggestion is not shown");
        assertThat(result.diffNotes()).hasSize(8);
    }

    @Test
    void compose_underTheCap_noOverflowTail() {
        List<ValidatedObservation> observations = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            observations.add(negativeObservation("nudge-" + i, "Minor nudge " + i, Severity.MINOR));
        }

        DeliveryContent result = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        assertThat(reachedTheDeveloper(result)).contains("Minor nudge 1", "Minor nudge 2", "Minor nudge 3");
        assertThat(result.mrNote()).doesNotContain("more minor suggestion");
        assertThat(result.diffNotes()).hasSize(3);
    }

    @Test
    void compose_equalBreadthUsesStableIdentityToChooseNudges() {
        List<ValidatedObservation> observations = new ArrayList<>();
        observations.add(negativeObservation("z-low", "Z nudge", Severity.MINOR));
        observations.add(negativeObservation("a-high", "A nudge", Severity.MINOR));
        observations.add(negativeObservation("b-high", "B nudge", Severity.MINOR));
        observations.add(negativeObservation("c-high", "C nudge", Severity.MINOR));

        DeliveryContent result = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        String mrNote = result.mrNote();
        assertThat(reachedTheDeveloper(result))
                .contains("A nudge", "B nudge", "C nudge")
                .doesNotContain("Z nudge");
    }

    @Test
    void compose_keepsMinorOverInfo_infoIsTheFirstToCollapse() {
        List<ValidatedObservation> observations = new ArrayList<>();
        observations.add(negativeObservation("minor-1", "Minor one", Severity.MINOR));
        observations.add(negativeObservation("minor-2", "Minor two", Severity.MINOR));
        observations.add(negativeObservation("minor-3", "Minor three", Severity.MINOR));
        observations.add(negativeObservation("info-1", "Info one", Severity.INFO));
        observations.add(negativeObservation("info-2", "Info two", Severity.INFO));

        DeliveryContent result = DeliveryComposer.compose(observations, ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        String mrNote = result.mrNote();
        assertThat(reachedTheDeveloper(result)).contains("2 more minor suggestions are not shown");
        assertThat(reachedTheDeveloper(result)).contains("Minor one");
        assertThat(reachedTheDeveloper(result)).contains("Minor two");
        assertThat(reachedTheDeveloper(result)).contains("Minor three");
        assertThat(mrNote).doesNotContain("Info one");
        assertThat(mrNote).doesNotContain("Info two");
    }

    @Test
    void undesirablePracticeObservedObservationIsTreatedAsAProblem() {
        JsonNode evidence =
                buildEvidence(List.of(new LocationSpec("Views/StockView.swift", 42)), List.of("let u = URL(s)!"));
        ValidatedObservation asProblemObservation = new ValidatedObservation(
                "uses-force-unwrap",
                "Force-unwrap present in changed code",
                Presence.PRESENT,
                Assessment.BAD,
                Severity.MAJOR,
                evidence,
                "Force-unwrapping crashes on nil.");
        ValidatedObservation asStrengthObservation = new ValidatedObservation(
                "uses-force-unwrap",
                "Force-unwrap present in changed code",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.MAJOR,
                evidence,
                "Force-unwrapping crashes on nil.");

        DeliveryContent asProblem = DeliveryComposer.compose(List.of(asProblemObservation), ArtifactKinds.PULL_REQUEST);
        DeliveryContent asStrength =
                DeliveryComposer.compose(List.of(asStrengthObservation), ArtifactKinds.PULL_REQUEST);

        assertThat(asProblem).isNotNull();
        assertThat(asProblem.diffNotes())
                .as("(PRESENT, BAD) is a problem → inline diff note")
                .isNotEmpty();
        assertThat(reachedTheDeveloper(asProblem)).contains("Force-unwrap present in changed code");

        assertThat(asStrength).isNotNull();
        assertThat(asStrength.diffNotes())
                .as("(PRESENT, GOOD) is a strength → no problem diff note")
                .isEmpty();
    }

    @Test
    void stripsGraderMechanicsLeakFromStudentNote() {
        String leakyReasoning = String.join(
                " ",
                "The 28-file spread means a reviewer cannot review this as a single coherent change.",
                "Per the fixed bucketing: >20 files → MAJOR, nowhere near the 70% threshold for downgrade.",
                "This triggers the largeness gate (signal ii — >=3 distinct parts in prose), so this is a non-epic body.",
                "Combined severity is MAJOR (the most severe sub-result).",
                "This matches the significance catalogue entry 'AUTH / SECURITY MECHANISM'.",
                "But diff_stat.txt lists 28 files and diff_summary.md shows 28 changed files — a material disagreement, so the diff is trusted.",
                "After scanning metadata.body, no sub_issues_total rollup is present (sub_issues_total is null).");
        ValidatedObservation leaky = negativeObservation(
                "scope-one-reviewable-change",
                "28 files spread degrades review effectiveness",
                Severity.MAJOR,
                List.of(new LocationSpec("Views/Foo.swift", 10)),
                List.of("x"),
                leakyReasoning);

        DeliveryContent result = DeliveryComposer.compose(List.of(leaky), ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        String note = result.mrNote() + "\n"
                + result.diffNotes().stream().map(DiffNote::body).collect(Collectors.joining("\n"));
        assertThat(note).contains("28 files spread degrades review effectiveness");
        assertThat(note).contains("reviewer cannot review this as a single coherent change");
        for (String leak : new String[] {
            "Per the fixed bucketing",
            "→ MAJOR",
            "70% threshold",
            "largeness gate",
            "signal ii",
            "non-epic body",
            "Combined severity",
            "most severe sub-result",
            "significance catalogue",
            "diff_stat.txt",
            "diff_summary.md",
            "material disagreement",
            "so the diff is trusted",
            "After scanning",
            "metadata.body",
            "sub_issues_total",
            "rollup",
        }) {
            assertThat(note).as("leak token must be scrubbed: %s", leak).doesNotContain(leak);
        }
    }

    @Test
    void suppressesYouWroteQuoteWhenEvidenceCarriesGraderMechanics() {
        ValidatedObservation f = negativeObservation(
                "mr-description-quality",
                "PR body lacks a quotable WHY",
                Severity.MAJOR,
                List.of(), // no code location → metadata "You wrote: “…”" path
                List.of(
                        "diff_stat.txt lists 28 changed files — metadata.changed_files=14 is stale (material disagreement); trusting the diff"),
                "The body enumerates what changed but never states why.");

        DeliveryContent result = DeliveryComposer.compose(List.of(f), ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        String note = result.mrNote();
        assertThat(note).doesNotContain("You wrote:");
        assertThat(note).doesNotContain("diff_stat.txt");
        assertThat(note).doesNotContain("material disagreement");
        assertThat(note).contains("PR body lacks a quotable WHY");
        assertThat(note).contains("enumerates what changed but never states why");
    }

    @Test
    void compose_synthesizedDiffNote_carriesObservationFingerprint() {
        ValidatedObservation stamped = negativeObservation(
                        "code-hygiene",
                        "Dead code in view",
                        Severity.MINOR,
                        List.of(new LocationSpec("Views/DashboardView.swift", 15)),
                        null,
                        "Commented-out code adds noise.")
                .withKeys(new ObservationKeys("occ-corr-synth-123", "corr-synth-123"));

        DeliveryContent result = DeliveryComposer.compose(List.of(stamped));

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).recurrenceKey()).isEqualTo("corr-synth-123");
    }

    @Test
    void compose_synthesizedDiffNote_anchorPathIsRepoRelativised() {
        var f = negativeObservation(
                "code-hygiene",
                "Unused import",
                Severity.MINOR,
                List.of(new LocationSpec("inputs/sources/scm/repo/src/components/Button.tsx", 1)),
                null,
                "Remove unused imports.");

        DeliveryContent result = DeliveryComposer.compose(List.of(f));

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).filePath()).isEqualTo("src/components/Button.tsx");
    }

    private static final String SCOPE_WHY =
            "A reviewer can only hold so much in their head at once; a focused change gets read carefully.";

    @Test
    void compose_withWhyBySlug_emptyMapIsBehaviourIdentical() {
        var f = negativeObservation(
                "scope-one-reviewable-change",
                "Change spans many unrelated concerns",
                Severity.MAJOR,
                List.of(),
                List.of(),
                "Touches three concerns.");

        String withoutMap = note(DeliveryComposer.compose(List.of(f), ArtifactKinds.PULL_REQUEST));
        String withEmptyMap = note(DeliveryComposer.compose(List.of(f), ArtifactKinds.PULL_REQUEST, Map.of()));

        assertThat(withEmptyMap).isEqualTo(withoutMap);
        assertThat(withEmptyMap).doesNotContain("Why this matters");
    }

    @Test
    void compose_withWhyBySlug_skipsPrincipleOnInfoNudge() {
        var info = negativeObservation(
                "leaves-the-code-clean-with-intent-revealing-comments",
                "A stray TODO remains",
                Severity.INFO,
                List.of(),
                List.of(),
                "One leftover TODO.");

        DeliveryContent result = DeliveryComposer.compose(
                List.of(info),
                ArtifactKinds.PULL_REQUEST,
                Map.of(
                        "leaves-the-code-clean-with-intent-revealing-comments",
                        "Intent-revealing code lowers the next reader's cost."));

        assertThat(result).isNotNull();
        assertThat(result.mrNote()).doesNotContain("Why this matters");
    }

    private ValidatedObservation positiveWithReasoning(String slug, String reasoning) {
        return new ValidatedObservation(
                slug,
                humanizeTitle(slug) + " (positive)",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                reasoning);
    }

    @Test
    void compose_allGoodPath_noPrincipleWhenNoneAuthored() {
        var observed = List.of(positiveWithReasoning("scope-one-reviewable-change", "The change stays focused."));

        String note = note(DeliveryComposer.compose(observed, ArtifactKinds.PULL_REQUEST, Map.of()));

        assertThat(note).contains("What's working well here");
        assertThat(note).doesNotContain("_Why this matters:_");
    }

    private static final String REAL_DIFF =
            "diff --git a/Sources/Capture/DepthData.swift b/Sources/Capture/DepthData.swift\n"
                    + "--- a/Sources/Capture/DepthData.swift\n"
                    + "+++ b/Sources/Capture/DepthData.swift\n"
                    + "@@ -10,3 +10,4 @@\n"
                    + " struct DepthData {\n"
                    + "+    let confidence: Float\n"
                    + " }\n";

    @Test
    void groundingGuard_hallucinatedPath_anchorDropped_observationStillDelivers() {
        ValidatedObservation hallucinated = negativeObservation(
                "code-hygiene",
                "Dead code",
                Severity.MINOR,
                List.of(new LocationSpec("Sources/Ghost/FrameRecorder.swift", 76)),
                List.of("let x = 0"),
                "There is dead code here.");

        DeliveryContent result =
                DeliveryComposer.compose(List.of(hallucinated), ArtifactKinds.PULL_REQUEST, Map.of(), REAL_DIFF);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).isEmpty();
        assertThat(result.mrNote()).contains("Dead code");
    }

    @Test
    void groundingGuard_realPathAndSnippet_anchorKept() {
        ValidatedObservation grounded = negativeObservation(
                "code-hygiene",
                "Missing doc on new field",
                Severity.MINOR,
                List.of(new LocationSpec("Sources/Capture/DepthData.swift", 11)),
                List.of("let confidence: Float"),
                "The new field is undocumented.");

        DeliveryContent result =
                DeliveryComposer.compose(List.of(grounded), ArtifactKinds.PULL_REQUEST, Map.of(), REAL_DIFF);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).filePath()).isEqualTo("Sources/Capture/DepthData.swift");
    }

    @Test
    void groundingGuard_realPathButSnippetNotInHunk_anchorDropped() {
        ValidatedObservation fabricatedSnippet = negativeObservation(
                "code-hygiene",
                "Phantom evidence",
                Severity.MINOR,
                List.of(new LocationSpec("Sources/Capture/DepthData.swift", 11)),
                List.of("deleteEverything() // never written"),
                "This line is a problem.");

        DeliveryContent result =
                DeliveryComposer.compose(List.of(fabricatedSnippet), ArtifactKinds.PULL_REQUEST, Map.of(), REAL_DIFF);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).isEmpty(); // ungrounded snippet ⇒ no inline anchor
        assertThat(result.mrNote()).contains("Phantom evidence"); // observation still delivered in summary
    }

    @Test
    void groundingGuard_issueArtifact_forcesNoFileLocus() {
        ValidatedObservation issueObservation = negativeObservation(
                "issue-states-an-actionable-problem",
                "Vague problem statement",
                Severity.MINOR,
                List.of(new LocationSpec("metadata.json", 1, "scm.issue.core")),
                List.of("\"title\": \"do stuff\""),
                "The issue does not state a concrete problem.");

        DeliveryContent result = DeliveryComposer.compose(
                List.of(issueObservation),
                ArtifactKinds.ISSUE,
                Map.of(),
                null // issues have no diff; force-no-locus still applies via the ISSUE branch
                );

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).isEmpty();
        assertThat(result.mrNote()).contains("Vague problem statement");
    }

    @Test
    void groundingGuard_noDiffSupplied_isNoOp_anchorKept() {
        ValidatedObservation observation = negativeObservation(
                "code-hygiene",
                "Some inline issue",
                Severity.MINOR,
                List.of(new LocationSpec("Sources/Whatever.swift", 5)),
                List.of("anything"),
                "An inline issue.");

        DeliveryContent result =
                DeliveryComposer.compose(List.of(observation), ArtifactKinds.PULL_REQUEST, Map.of(), (String) null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1); // no-op guard ⇒ anchor kept
    }

    @Test
    void compose_withheld_reportsCappedImprovementTailAsPolicyFloorDrop() {
        List<ValidatedObservation> observations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            observations.add(negativeObservation(
                            "nudge-" + i,
                            "Nudge " + i,
                            Severity.MINOR,
                            List.of(new LocationSpec("Views/N" + i + ".swift", 10 + i)),
                            null,
                            "Reasoning " + i + ".")
                    .withKeys(new ObservationKeys("occ-" + i, "rk-" + i)));
        }

        DeliveryContent result = DeliveryComposer.compose(observations);

        assertThat(result).isNotNull();
        assertThat(result.withheld())
                .hasSize(2)
                .allSatisfy(w -> assertThat(w.reason()).isEqualTo(FeedbackSuppressionReason.VOLUME_CAPPED));
        Map<String, String> titleByKey = observations.stream()
                .collect(Collectors.toMap(ValidatedObservation::occurrenceKey, ValidatedObservation::summary));
        for (WithheldObservation w : result.withheld()) {
            assertThat(result.mrNote()).doesNotContain(titleByKey.get(w.occurrenceKey()));
        }
    }

    @Test
    void compose_withheld_addressesOneObservation_notTheWholeLocus() {
        List<ValidatedObservation> sameLocus = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sameLocus.add(negativeObservation(
                            "ships-tests-with-the-change",
                            "Gap " + i,
                            Severity.MINOR,
                            List.of(new LocationSpec("src/Foo.java", 10)),
                            null,
                            "A gap.")
                    .withKeys(new ObservationKeys("occ-" + i, "shared-locus")));
        }

        List<WithheldObservation> withheld =
                content(DeliveryComposer.compose(sameLocus)).withheld();

        assertThat(withheld).hasSize(1);
        assertThat(withheld.get(0).occurrenceKey()).startsWith("occ-").isNotEqualTo("shared-locus");
    }

    @Test
    void compose_withheld_reportsCoOccurrenceDedupAsComposerDeduped() {
        ValidatedObservation redundant = negativeObservation(
                        "ready-and-traceable-handoff",
                        "DoD checkbox claims tests pass",
                        Severity.MAJOR,
                        List.of(),
                        null,
                        "The DoD claims all tests pass but no tests changed.")
                .withKeys(new ObservationKeys("occ-rk-redundant", "rk-redundant"));
        ValidatedObservation preferred = negativeObservation(
                        "ships-tests-with-the-change",
                        "No tests shipped with the change",
                        Severity.MAJOR,
                        List.of(new LocationSpec("Sources/Feature.swift", 12)),
                        null,
                        "The change adds behaviour without tests.")
                .withKeys(new ObservationKeys("occ-rk-preferred", "rk-preferred"));

        DeliveryContent result = DeliveryComposer.compose(List.of(redundant, preferred));

        assertThat(result).isNotNull();
        assertThat(result.withheld())
                .containsExactly(
                        new WithheldObservation("occ-rk-redundant", FeedbackSuppressionReason.COMPOSER_DEDUPED));
    }

    @Test
    void compose_withheld_skipsUnkeyedObservations() {
        List<ValidatedObservation> observations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            observations.add(negativeObservation(
                    "nudge-" + i,
                    "Nudge " + i,
                    Severity.MINOR,
                    List.of(new LocationSpec("Views/N" + i + ".swift", 10 + i)),
                    null,
                    "Reasoning " + i + "."));
        }

        DeliveryContent result = DeliveryComposer.compose(observations);

        assertThat(result).isNotNull();
        assertThat(result.withheld()).isEmpty();
    }

    @Test
    void compose_withheld_epicStructureDedupOnIssue_reportsComposerDeduped() {
        ValidatedObservation scoped = negativeObservation(
                        "issue-scoped-to-single-concern",
                        "Issue bundles several concerns",
                        Severity.MAJOR,
                        List.of(),
                        null,
                        "The issue mixes several concerns.")
                .withKeys(new ObservationKeys("occ-rk-scoped", "rk-scoped"));
        ValidatedObservation checkable = negativeObservation(
                        "issue-has-checkable-outcome",
                        "No checkable outcome",
                        Severity.MINOR,
                        List.of(),
                        null,
                        "The issue has no checkable outcome.")
                .withKeys(new ObservationKeys("occ-rk-checkable", "rk-checkable"));

        DeliveryContent result = DeliveryComposer.compose(List.of(scoped, checkable), ArtifactKinds.ISSUE);

        assertThat(result).isNotNull();
        assertThat(result.withheld())
                .containsExactly(
                        new WithheldObservation("occ-rk-checkable", FeedbackSuppressionReason.COMPOSER_DEDUPED));
    }

    private static final String COMPOSED_BODY = "Nothing in this change exercises the tax-exempt branch you added.";
    private static final String COMPOSED_NEXT_STEP =
            "Write the assertion that distinguishes the exempt case, then run the suite.";

    private ComposedFeedbackUnit inContextUnit(String slug, String title, String body, String nextStep) {
        return inContextUnit(
                slug,
                title,
                nextStep,
                new ComposedFeedbackUnit.InContextPlacement(
                        ComposedFeedbackUnit.InContextPlacement.PlacementKind.DIFF,
                        new ComposedFeedbackUnit.ResolvedAnchor("obs-0", 0, "Billing/Invoice.java", "NEW", 42, 42)));
    }

    private ComposedFeedbackUnit artifactInContextUnit(String slug, String title, String nextStep) {
        return inContextUnit(
                slug,
                title,
                nextStep,
                new ComposedFeedbackUnit.InContextPlacement(
                        ComposedFeedbackUnit.InContextPlacement.PlacementKind.ARTIFACT, null));
    }

    private ComposedFeedbackUnit inContextUnit(
            String slug, String title, String nextStep, ComposedFeedbackUnit.InContextPlacement placement) {
        return new ComposedFeedbackUnit(
                FeedbackChannel.IN_CONTEXT,
                slug,
                List.of("obs-0"),
                ComposedFeedbackUnit.Action.NEW,
                null,
                null,
                title,
                null,
                nextStep,
                null,
                placement);
    }

    @Test
    @DisplayName("an observation whose reasoning scrubs to nothing places no inline note at all")
    void compose_reasoningScrubbedToNothing_placesNoInlineNoteRatherThanABareHeader() {
        ValidatedObservation onlyMeta = negativeObservation(
                "ships-tests-with-the-change",
                "New branch ships without a test",
                Severity.MAJOR,
                List.of(new LocationSpec("Billing/Invoice.java", 42)),
                List.of("if (customer.isTaxExempt()) {"),
                "Per the fixed bucketing: >20 files \u2192 MAJOR, nowhere near the 70% threshold for downgrade.");

        DeliveryContent result = DeliveryComposer.compose(List.of(onlyMeta), ArtifactKinds.PULL_REQUEST);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).isEmpty();
        assertThat(result.mrNote()).contains("New branch ships without a test").contains("Billing/Invoice.java:42");
    }

    @Test
    @DisplayName("a quoted block is wrapped in a fence longer than any it contains")
    void quotedEvidence_isWrappedInAFenceThatOutrunsTheQuote() {
        assertThat(DeliveryComposer.fenceFor("int x = 1;")).isEqualTo("```");
        assertThat(DeliveryComposer.fenceFor("```bash\npnpm run check\n```"))
                .as("a quote that is itself a fenced block closes a three-backtick wrapper early")
                .isEqualTo("````");
        assertThat(DeliveryComposer.fenceFor("a ````` b")).isEqualTo("``````");
    }

    private ValidatedObservation untestedBranchObservation() {
        return negativeObservation(
                "ships-tests-with-the-change",
                "New branch ships without a test",
                Severity.MAJOR,
                List.of(new LocationSpec("Billing/Invoice.java", 42)),
                List.of("if (customer.isTaxExempt()) {"),
                "MEASURED REASONING: the change adds a branch and no test covers it.");
    }

    @Test
    void compose_composedInContextUnit_usesServerEvidenceAndTheComposedNextStep() {
        DeliveryContent result = DeliveryComposer.compose(
                List.of(untestedBranchObservation()),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(inContextUnit(
                        "ships-tests-with-the-change", "Untested branch", COMPOSED_BODY, COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        String note = result.diffNotes().get(0).body();
        assertThat(note).contains(COMPOSED_NEXT_STEP).doesNotContain(COMPOSED_BODY);
        assertThat(note).doesNotContain("MEASURED REASONING").doesNotContain("MEASURED GUIDANCE");
        assertThat(note).contains("Untested branch").doesNotContain("New branch ships without a test");
        assertThat(note).contains("🟠");
        assertThat(reachedTheDeveloper(result))
                .contains("Untested branch")
                .doesNotContain("New branch ships without a test");
    }

    @Test
    void compose_noComposedUnit_fallsBackToTheMeasurementTimeReasoningAlone() {
        DeliveryContent result = DeliveryComposer.compose(
                List.of(untestedBranchObservation()), ArtifactKinds.PULL_REQUEST, Map.of(), null, List.of(), null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        String body = result.diffNotes().get(0).body();
        assertThat(body).contains("MEASURED REASONING").contains("New branch ships without a test");
        assertThat(body).doesNotContain("MEASURED GUIDANCE");
    }

    @Test
    void compose_inContextIgnoresLegacyBodyAndUsesTheNextStep() {
        DeliveryContent result = DeliveryComposer.compose(
                List.of(untestedBranchObservation()),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(inContextUnit(
                        "ships-tests-with-the-change",
                        "Untested branch",
                        "The practice requires an assertion for every new branch.",
                        COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        String note = result.diffNotes().get(0).body();
        assertThat(note).contains(COMPOSED_NEXT_STEP);
        assertThat(note).doesNotContain("The practice requires").doesNotContain("MEASURED REASONING");
    }

    @Test
    void compose_inContextNeverRendersTheLegacyBody() {
        DeliveryContent result = DeliveryComposer.compose(
                List.of(untestedBranchObservation()),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(inContextUnit(
                        "ships-tests-with-the-change",
                        "Untested branch",
                        COMPOSED_BODY + " The practice requires an assertion for every new branch.",
                        COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).body())
                .contains(COMPOSED_NEXT_STEP)
                .doesNotContain(COMPOSED_BODY)
                .doesNotContain("The practice requires");
    }

    @Test
    void compose_composedUnitForAPracticeThatCannotBeAnchored_landsInTheSummaryInsteadOfInventingAPlacement() {
        ValidatedObservation f = negativeObservation(
                "describe-what-and-why",
                "Description does not say why",
                Severity.MAJOR,
                List.of(),
                null,
                "MEASURED REASONING: the description lists what changed only.");

        DeliveryContent result = DeliveryComposer.compose(
                List.of(f),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(artifactInContextUnit("describe-what-and-why", "Unexplained change", COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).isEmpty();
        assertThat(result.mrNote())
                .contains("Unexplained change")
                .doesNotContain(COMPOSED_BODY)
                .contains(COMPOSED_NEXT_STEP)
                .doesNotContain("MEASURED REASONING");
    }

    @Test
    void compose_twoLociOfOnePractice_rendersItsSingleComposedMessageOnceAtTheMostSevereLocus() {
        ValidatedObservation severe = untestedBranchObservation();
        ValidatedObservation lesser = negativeObservation(
                "ships-tests-with-the-change",
                "Second untested branch",
                Severity.MINOR,
                List.of(new LocationSpec("Billing/Refund.java", 9)),
                List.of("if (order.isRefundable()) {"),
                "SECOND LOCUS REASONING: another branch with no test.");

        DeliveryContent result = DeliveryComposer.compose(
                List.of(lesser, severe),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(inContextUnit(
                        "ships-tests-with-the-change", "Untested branch", COMPOSED_BODY, COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(2);
        assertThat(result.diffNotes().stream()
                        .filter(n -> n.body().contains(COMPOSED_NEXT_STEP))
                        .count())
                .isEqualTo(1);
        assertThat(result.diffNotes().get(0).filePath()).isEqualTo("Billing/Invoice.java");
        assertThat(result.diffNotes().get(0).body())
                .contains(COMPOSED_NEXT_STEP)
                .doesNotContain(COMPOSED_BODY);
        assertThat(result.diffNotes().get(1).body()).contains("SECOND LOCUS REASONING");
    }

    @Test
    void compose_withholdUnit_leavesThePracticeOnTodaysRenderingRatherThanSilencingIt() {
        ComposedFeedbackUnit withheld = new ComposedFeedbackUnit(
                FeedbackChannel.IN_CONTEXT,
                "ships-tests-with-the-change",
                List.of("obs-0"),
                ComposedFeedbackUnit.Action.WITHHOLD,
                null,
                ComposedFeedbackUnit.WithholdReason.ALREADY_SAID,
                null,
                null,
                null,
                null,
                null);

        DeliveryContent result = DeliveryComposer.compose(
                List.of(untestedBranchObservation()),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(withheld),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).body()).contains("MEASURED REASONING");
    }

    @Test
    void compose_inAppUnitForTheSamePractice_isNotBorrowedByTheInContextLane() {
        ComposedFeedbackUnit inApp = new ComposedFeedbackUnit(
                FeedbackChannel.IN_APP,
                "ships-tests-with-the-change",
                List.of("prior:ships-tests-with-the-change"),
                ComposedFeedbackUnit.Action.NEW,
                null,
                null,
                "A habit across three changes",
                "IN_APP BODY: this keeps happening.",
                "IN_APP NEXT STEP: write the test first next time.",
                null,
                null);

        DeliveryContent result = DeliveryComposer.compose(
                List.of(untestedBranchObservation()), ArtifactKinds.PULL_REQUEST, Map.of(), null, List.of(inApp), null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        assertThat(result.diffNotes().get(0).body())
                .contains("MEASURED REASONING")
                .doesNotContain("IN_APP BODY");
    }

    @Test
    void compose_composedUnitOnTheAgentsOwnSuggestedAnchor_keepsThePlacementAndTakesTheWords() {
        ValidatedObservation f = new ValidatedObservation(
                "ships-tests-with-the-change",
                "New branch ships without a test",
                Presence.ABSENT,
                Assessment.BAD,
                Severity.MAJOR,
                buildEvidence(
                        List.of(new LocationSpec("Billing/Invoice.java", 42)),
                        List.of("if (customer.isTaxExempt()) {")),
                "MEASURED REASONING: the change adds a branch and no test covers it.");

        DeliveryContent result = DeliveryComposer.compose(
                List.of(f),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(inContextUnit(
                        "ships-tests-with-the-change", "Untested branch", COMPOSED_BODY, COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).hasSize(1);
        DiffNote note = result.diffNotes().get(0);
        assertThat(note.filePath()).isEqualTo("Billing/Invoice.java");
        assertThat(note.startLine()).isEqualTo(42);
        assertThat(note.endLine()).isEqualTo(42);
        assertThat(note.body())
                .doesNotContain(COMPOSED_BODY)
                .contains(COMPOSED_NEXT_STEP)
                .doesNotContain("SUGGESTED NOTE BODY");
    }

    @Test
    void compose_strengthsOnlyRun_prefersTheComposedMessageInTheBullet() {
        ValidatedObservation good = new ValidatedObservation(
                "ships-tests-with-the-change",
                "Tests ship with the change",
                Presence.PRESENT,
                Assessment.GOOD,
                Severity.INFO,
                null,
                "MEASURED REASONING: the new branch is covered.");

        DeliveryContent result = DeliveryComposer.compose(
                List.of(good),
                ArtifactKinds.PULL_REQUEST,
                Map.of(),
                null,
                List.of(inContextUnit(
                        "ships-tests-with-the-change", "Tests landed with it", COMPOSED_BODY, COMPOSED_NEXT_STEP)),
                null);

        assertThat(result).isNotNull();
        assertThat(result.diffNotes()).isEmpty();
        assertThat(result.mrNote())
                .doesNotContain(COMPOSED_BODY)
                .contains(COMPOSED_NEXT_STEP)
                .doesNotContain("MEASURED REASONING");
        assertThat(result.mrNote())
                .as("a title with no terminal stop must not run into the step that follows it")
                .contains("Tests landed with it. " + COMPOSED_NEXT_STEP);
    }
}
