package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout.REPO_MOUNT_RELATIVE;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.feedback.DeveloperTextSanitizer;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Renders admitted observations and composed in-context feedback for artifact delivery. */
class DeliveryComposer {

    static final int MAX_DIFF_NOTE_BODY_LENGTH = 2_000;

    static final int MAX_IMPROVEMENT_SUGGESTIONS = 3;
    static final Set<String> NON_INLINABLE_PRACTICES =
            Set.of("describe-what-and-why", "commits-are-atomic-and-cohesive", "commit-subjects-explain-each-change");
    private static final Set<String> EPIC_STRUCTURE_PRACTICES =
            Set.of("issue-scoped-to-single-concern", "issue-has-checkable-outcome");
    private static final Map<String, String> CO_OCCURRENCE_REDUNDANT_TO_PREFERRED =
            Map.ofEntries(Map.entry("ready-and-traceable-handoff", "ships-tests-with-the-change"));

    private static String repoRelative(String path) {
        return path.startsWith(REPO_MOUNT_RELATIVE) ? path.substring(REPO_MOUNT_RELATIVE.length()) : path;
    }

    /** Package-private: {@link ReviewCoverage} reads it so the two cannot disagree on what a problem is. */
    static boolean isProblem(ValidatedObservation f) {
        return f.assessment() == Assessment.BAD;
    }

    private static boolean isStrength(ValidatedObservation f) {
        return f.assessment() == Assessment.GOOD;
    }

    @Nullable
    static DeliveryContent compose(@Nullable List<ValidatedObservation> observations) {
        return compose(observations, ArtifactKinds.PULL_REQUEST);
    }

    @Nullable
    static DeliveryContent compose(@Nullable List<ValidatedObservation> observations, ArtifactKind artifact) {
        return compose(observations, artifact, Map.of());
    }

    @Nullable
    static DeliveryContent compose(
            @Nullable List<ValidatedObservation> observations, ArtifactKind artifact, Map<String, String> whyBySlug) {
        return compose(observations, artifact, whyBySlug, GroundingContext.none(), List.of(), null);
    }

    @Nullable
    static DeliveryContent compose(
            @Nullable List<ValidatedObservation> observations,
            ArtifactKind artifact,
            Map<String, String> whyBySlug,
            @Nullable String unifiedDiff) {
        return compose(observations, artifact, whyBySlug, unifiedDiff, List.of(), null);
    }

    @Nullable
    static DeliveryContent compose(
            @Nullable List<ValidatedObservation> observations,
            ArtifactKind artifact,
            Map<String, String> whyBySlug,
            @Nullable String unifiedDiff,
            List<ComposedFeedbackUnit> composed,
            @Nullable String lead) {
        return compose(
                observations, artifact, whyBySlug, GroundingContext.fromDiff(artifact, unifiedDiff), composed, lead);
    }

    @Nullable
    private static DeliveryContent compose(
            @Nullable List<ValidatedObservation> observations,
            ArtifactKind artifact,
            Map<String, String> whyBySlug,
            GroundingContext grounding,
            List<ComposedFeedbackUnit> composed,
            @Nullable String lead) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        // Shared across the summary and inline notes so a slug's "Why this matters" lands exactly once.
        Set<String> emittedWhy = new HashSet<>();

        // Reported on the DeliveryContent so the ledger marks these SUPPRESSED, not DELIVERED.
        List<ValidatedObservation> dedupDropped = new ArrayList<>();
        List<ValidatedObservation> capDropped = new ArrayList<>();

        List<ValidatedObservation> negatives = observations.stream()
                .filter(DeliveryComposer::isProblem)
                .sorted(Comparator.comparingInt(f -> severity(f).ordinal()))
                .toList();

        if (ArtifactKinds.ISSUE.equals(artifact)) {
            List<ValidatedObservation> before = negatives;
            negatives = dedupEpicStructure(negatives);
            dedupDropped.addAll(identityDiff(before, negatives));
        }

        {
            List<ValidatedObservation> before = negatives;
            negatives = dedupCoOccurringNegatives(negatives);
            dedupDropped.addAll(identityDiff(before, negatives));
        }

        // Every blocking (CRITICAL/MAJOR) observation is kept; only the non-blocking tail is capped (see
        // capImprovementTail). The capped list, not the raw one, flows into the partition and diff notes
        // below, so a dropped nudge leaves no inline comment either.
        int improvementOverflow = 0;
        long blockingTotal = negatives.stream()
                .filter(f -> f.severity() == Severity.CRITICAL || f.severity() == Severity.MAJOR)
                .count();
        long improvementTotal = negatives.size() - blockingTotal;
        if (improvementTotal > MAX_IMPROVEMENT_SUGGESTIONS) {
            List<ValidatedObservation> before = negatives;
            negatives = capImprovementTail(negatives);
            capDropped.addAll(identityDiff(before, negatives));
            improvementOverflow = (int) (improvementTotal - MAX_IMPROVEMENT_SUGGESTIONS);
        }

        if (negatives.isEmpty()) {
            // Ranked best-attested first, so the strengths that survive the cap are the ones we saw in the
            // most of the work, and so a practice's single composed message is claimed by its widest
            // signal. Strengths carry no severity, so breadth is the only ranking dimension there is here.
            List<ValidatedObservation> observed = observations.stream()
                    .filter(DeliveryComposer::isStrength)
                    .sorted(ObservationOrder.bestAttestedFirst())
                    .toList();
            if (observed.isEmpty()) {
                // Every observation NOT_APPLICABLE or INCONCLUSIVE: nothing was actually assessed, so deliver
                // nothing rather than a misleading "nothing to change here" all-clear.
                return null;
            }
            Rendering rendering = new Rendering(whyBySlug, emittedWhy, ComposedNotes.claim(observed, composed), lead);
            return new DeliveryContent(composeNoIssuesNote(observed, rendering), List.of(), List.of());
        }

        // Issues carry no diff, so every issue observation must expand in full in the note itself rather than
        // demote to a diff note that silently vanishes.
        boolean inlineSupported = ArtifactKinds.hasInlineLane(artifact);
        List<ValidatedObservation> inlinable = new ArrayList<>();
        List<ValidatedObservation> nonInlinable = new ArrayList<>();
        for (ValidatedObservation f : negatives) {
            if (inlineSupported && !isNonInlinable(f) && !hasArtifactPlacement(f.practiceSlug(), composed)) {
                inlinable.add(f);
            } else {
                nonInlinable.add(f);
            }
        }

        // Claimed over the severity-sorted negatives, BEFORE either surface renders, so the summary and
        // the inline notes agree on which locus carries a practice's composed message.
        Rendering rendering = new Rendering(whyBySlug, emittedWhy, ComposedNotes.claim(negatives, composed), lead);

        // The notes on the diff are built first, because a finding that could not be placed on a line —
        // capped, or its anchor no longer in the diff — has to fall back into the summary rather than
        // vanish between the two surfaces.
        var placed = collectDiffNotes(inlinable, rendering, grounding);
        List<ValidatedObservation> summarised = new ArrayList<>(nonInlinable);
        summarised.addAll(placed.unplaced());
        summarised.sort(ObservationOrder.worstFirstUnstored());

        String mrNote = composeMrNote(summarised, improvementOverflow, rendering);
        List<DiffNote> diffNotes = placed.notes();

        return new DeliveryContent(mrNote, diffNotes, withheldObservations(dedupDropped, capDropped));
    }

    private static boolean hasArtifactPlacement(String practiceSlug, List<ComposedFeedbackUnit> composed) {
        return composed.stream()
                .anyMatch(unit -> unit.channel() == FeedbackChannel.IN_CONTEXT
                        && unit.action() != ComposedFeedbackUnit.Action.WITHHOLD
                        && unit.practiceSlug().equals(practiceSlug)
                        && unit.placement() != null
                        && unit.placement().kind() == ComposedFeedbackUnit.InContextPlacement.PlacementKind.ARTIFACT);
    }

    private static List<ValidatedObservation> identityDiff(
            List<ValidatedObservation> before, List<ValidatedObservation> after) {
        if (before.size() == after.size()) {
            return List.of();
        }
        Set<ValidatedObservation> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(after);
        List<ValidatedObservation> dropped = new ArrayList<>(before.size() - after.size());
        for (ValidatedObservation f : before) {
            if (!kept.contains(f)) {
                dropped.add(f);
            }
        }
        return dropped;
    }

    private static List<PracticeDetectionResultParser.WithheldObservation> withheldObservations(
            List<ValidatedObservation> dedupDropped, List<ValidatedObservation> capDropped) {
        return Stream.concat(
                        dedupDropped.stream().map(f -> withheld(f, FeedbackSuppressionReason.COMPOSER_DEDUPED)),
                        capDropped.stream().map(f -> withheld(f, FeedbackSuppressionReason.VOLUME_CAPPED)))
                .filter(Objects::nonNull)
                .toList();
    }

    private static PracticeDetectionResultParser.@Nullable WithheldObservation withheld(
            ValidatedObservation f, FeedbackSuppressionReason reason) {
        String key = f.occurrenceKey();
        return key == null ? null : new PracticeDetectionResultParser.WithheldObservation(key, reason);
    }

    private static List<ValidatedObservation> dedupEpicStructure(List<ValidatedObservation> negatives) {
        long epicCount = negatives.stream()
                .filter(f -> EPIC_STRUCTURE_PRACTICES.contains(f.practiceSlug()))
                .count();
        if (epicCount < 2) {
            return negatives;
        }
        List<ValidatedObservation> kept = new ArrayList<>(negatives.size());
        boolean epicKept = false;
        for (ValidatedObservation f : negatives) {
            if (EPIC_STRUCTURE_PRACTICES.contains(f.practiceSlug())) {
                if (epicKept) {
                    continue;
                }
                epicKept = true;
            }
            kept.add(f);
        }
        return kept;
    }

    private static List<ValidatedObservation> dedupCoOccurringNegatives(List<ValidatedObservation> negatives) {
        Set<String> present =
                negatives.stream().map(ValidatedObservation::practiceSlug).collect(Collectors.toSet());
        Set<String> toDrop = CO_OCCURRENCE_REDUNDANT_TO_PREFERRED.entrySet().stream()
                .filter(e -> present.contains(e.getKey()) && present.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (toDrop.isEmpty()) {
            return negatives;
        }
        return negatives.stream()
                .filter(f -> !toDrop.contains(f.practiceSlug()))
                .toList();
    }

    private static List<ValidatedObservation> capImprovementTail(List<ValidatedObservation> negatives) {
        List<ValidatedObservation> blocking = new ArrayList<>();
        List<ValidatedObservation> improvements = new ArrayList<>();
        for (ValidatedObservation f : negatives) {
            if (f.severity() == Severity.CRITICAL || f.severity() == Severity.MAJOR) {
                blocking.add(f);
            } else {
                improvements.add(f);
            }
        }
        // Identity, not value-equality: ValidatedObservation is a record, so a value-set would collapse two
        // equal observations into one slot and the re-emit below would match both, overshooting the cap.
        Set<ValidatedObservation> keptImprovements = improvements.stream()
                .sorted(ObservationOrder.worstFirstUnstored())
                .limit(MAX_IMPROVEMENT_SUGGESTIONS)
                .collect(Collectors.toCollection(() -> Collections.newSetFromMap(new IdentityHashMap<>())));

        List<ValidatedObservation> kept = new ArrayList<>(blocking.size() + keptImprovements.size());
        for (ValidatedObservation f : negatives) {
            if (blocking.contains(f) || keptImprovements.contains(f)) {
                kept.add(f);
            }
        }
        return kept;
    }

    private static final int MAX_STRENGTH_REINFORCEMENTS = 3;
    private static final int STRENGTH_BUDGET = 280;

    private static final int LEAD_BUDGET = 240;

    /**
     * The opening sits where a reader trusts the message most, so it is held to a narrower contract than a
     * finding body: nothing that can restructure the comment around itself, no link, and no verdict on
     * merging — which the composer is never told and could only invent. A lead reaching for any of them is
     * dropped whole, and the note opens on its first finding instead.
     */
    private static final Pattern LEAD_REJECTED = Pattern.compile(
            "```|~~~|<!--|\\]\\(|\\]\\[|^\\s{0,3}(?:#{1,6}\\s|>|\\||[-+*]\\s|\\d+[.)]\\s|-{3,}|={3,})"
                    + "|\\b(?:lgtm|looks good to me|ship it|good to go|ready to (?:merge|ship)|safe to merge"
                    + "|before merging|(?:this|it) can merge|approv(?:e|es|ed|ing|al))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static String composeNoIssuesNote(List<ValidatedObservation> observed, Rendering rendering) {
        String opening = openingOf(rendering);
        // Already ranked most-certain first by the caller. A strength earns a bullet when there is
        // something to say about it — the composed message where the stage wrote one, and the
        // observation's own summary where it did not. Never the evidence rationale: that is written
        // for whoever audits the review, in the first person and about the search ("I walked all six
        // sink classes"), and a developer reading their own pull request is owed what was found about
        // their work rather than how the instrument looked for it.
        List<ValidatedObservation> withSomethingToSay = observed.stream()
                .filter(f -> rendering.noteFor(f) != null || !f.summary().isBlank())
                .toList();

        if (withSomethingToSay.isEmpty()) {
            return opening + "Reviewed against the active practices \u2014 nothing to change here.\n";
        }

        var bullets = new StringBuilder(1024);
        int shown = 0;
        for (ValidatedObservation f : withSomethingToSay) {
            if (shown >= MAX_STRENGTH_REINFORCEMENTS) break;
            ComposedNote note = rendering.noteFor(f);
            String summary = clampToSentenceBudget(
                    note == null ? sanitizeStudentText(f.summary()).strip() : note.title(), STRENGTH_BUDGET);
            if (summary.isBlank()) {
                // Reasoning was entirely grading-meta and scrubbed to nothing — skip rather than emit a
                // bare bullet with no observation behind it.
                continue;
            }
            String label = capitalize(f.practiceSlug().replace('-', ' '));
            bullets.append("- **").append(label).append(":** ").append(summary);
            // Only a composed message carries a forward step; a measurement writes none, so an uncomposed
            // strength is the observation alone. Bare, empty, or "No change needed." text degrades to the
            // same thing.
            String forward = clampToSentenceBudget(note == null ? "" : note.nextStep(), STRENGTH_BUDGET);
            if (!forward.isBlank() && !forward.replace(".", "").equalsIgnoreCase("No change needed")) {
                bullets.append(endSentence(summary)).append(' ').append(forward);
            }
            bullets.append("\n");
            shown++;
        }
        if (shown == 0) {
            return opening + "Reviewed against the active practices \u2014 nothing to change here.\n";
        }
        // The review's own opening already introduces the list; a second header would say it again, worse.
        String header = opening.isEmpty() ? "What's working well here, and how to keep building on it:\n\n" : "";
        return opening + header + bullets + "\n";
    }

    static String clampToSentenceBudget(String text, int maxLen) {
        if (text == null || text.isBlank() || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(text);
        int end = 0;
        for (int boundary = sentences.next(); boundary != BreakIterator.DONE; boundary = sentences.next()) {
            if (boundary > maxLen) break;
            end = boundary;
        }
        return end == 0
                ? truncateAtWordBoundary(text, maxLen)
                : text.substring(0, end).strip();
    }

    static String sanitizeStudentText(@Nullable String text) {
        return DeveloperTextSanitizer.sanitize(text);
    }

    static String stripEnvelopeCorruption(String text) {
        return DeveloperTextSanitizer.stripEnvelopeCorruption(text);
    }

    private static String truncateAtWordBoundary(String text, int maxLen) {
        int space = text.lastIndexOf(' ', maxLen);
        if (space > maxLen / 2) {
            return text.substring(0, space) + "...";
        }
        return text.substring(0, maxLen) + "...";
    }

    /** Model prose does not reliably end in a stop, so joining title and step on a space runs them together. */
    private static String endSentence(String text) {
        String trimmed = text.strip();
        return trimmed.isEmpty() || ".!?:".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0 ? "" : ".";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isNonInlinable(ValidatedObservation f) {
        if (NON_INLINABLE_PRACTICES.contains(f.practiceSlug())) {
            return true;
        }
        String location = extractPrimaryLocation(f);
        return location == null;
    }

    static String composeMrNote(List<ValidatedObservation> summarised, int improvementOverflow, Rendering rendering) {
        var sb = new StringBuilder(4096);

        sb.append(openingOf(rendering));
        appendExpanded(sb, summarised, rendering, false);

        if (improvementOverflow > 0) {
            sb.append(improvementOverflow)
                    .append(improvementOverflow == 1 ? " more minor suggestion is" : " more minor suggestions are")
                    .append(" not shown.\n\n");
        }

        return sb.toString();
    }

    private static void appendExpanded(
            StringBuilder sb,
            List<ValidatedObservation> observations,
            Rendering rendering,
            boolean separatorAfterLast) {
        for (int i = 0; i < observations.size(); i++) {
            ValidatedObservation f = observations.get(i);
            composeObservation(sb, f, rendering);
            boolean lastWasProse = extractPrimaryLocation(f) == null;
            boolean nextIsProse =
                    i + 1 < observations.size() && extractPrimaryLocation(observations.get(i + 1)) == null;
            boolean more = i < observations.size() - 1 || separatorAfterLast;
            if (more && !(lastWasProse && nextIsProse)) {
                sb.append("---\n\n");
            }
        }
    }

    /**
     * The one place every finding is visible at once. It survives a force-push, which the notes on the diff
     * do not, so a finding that carries its own comment is still named here.
     */

    /** The runner bounds the lead too, but that output is the model's; re-apply the bound rather than trust it. */
    private static String openingOf(Rendering rendering) {
        String lead = clampToSentenceBudget(
                        sanitizeStudentText(rendering.lead()).strip(), LEAD_BUDGET)
                .strip();
        return lead.isBlank() || LEAD_REJECTED.matcher(lead).find() ? "" : lead + "\n\n";
    }

    private static void appendObservationHeader(
            StringBuilder sb, ValidatedObservation f, boolean withLocation, Rendering rendering) {
        ComposedNote note = rendering.noteFor(f);
        String title = note == null || note.title() == null ? f.summary() : note.title();
        sb.append("**")
                .append(severityEmoji(severity(f)))
                .append(" ")
                .append(title)
                .append("**");
        if (withLocation) {
            String location = extractPrimaryLocation(f);
            if (location != null) {
                sb.append(" · `").append(location).append("`");
            }
        }
    }

    private static void composeObservation(StringBuilder sb, ValidatedObservation f, Rendering rendering) {
        // A finding about the pull request itself has no line to point at, so it reads as a sentence among
        // the ones the review wrote. Three bold headers in a row turn an opening paragraph into a form.
        if (extractPrimaryLocation(f) == null) {
            composeArtifactObservation(sb, f, rendering);
            return;
        }
        appendObservationHeader(sb, f, true, rendering);
        sb.append("\n\n");

        if (f.severity() == Severity.CRITICAL || f.severity() == Severity.MAJOR) {
            String snippet = extractPrimarySnippet(f);
            // Suppress the "You wrote:" quote when it carries grader mechanics instead of the student's
            // own artifact (the agent sometimes drops pipeline plumbing into the evidence field). And
            // metadata-field observations (title/body spans, flags) do not echo a quote at all: the agent's
            // metadata span is frequently a truncated heading or serialized boolean that reads as broken
            // output, so the quote is gated on a real code location too.
            if (snippet != null && !containsGraderMechanics(snippet) && extractPrimaryLocation(f) != null) {
                sb.append("You wrote:\n");
                String fence = fenceFor(snippet);
                sb.append(fence)
                        .append(detectLanguage(f))
                        .append("\n")
                        .append(snippet)
                        .append("\n")
                        .append(fence)
                        .append("\n\n");
            }
        }

        appendBody(sb, f, rendering);
    }

    private static void composeArtifactObservation(StringBuilder sb, ValidatedObservation f, Rendering rendering) {
        ComposedNote note = rendering.noteFor(f);
        String claim = sanitizeStudentText(note == null || note.title() == null ? f.summary() : note.title())
                .strip();
        String step = note == null ? "" : sanitizeStudentText(note.nextStep()).strip();
        if (claim.isBlank()) {
            claim = step;
            step = "";
        }
        if (claim.isBlank()) {
            return;
        }
        // Written as a clause so it reads as part of a sentence, which leaves the server to start it like
        // one. A note about the pull request lands between the review's own paragraphs, not under a header.
        sb.append(capitalize(claim));
        if (!step.isBlank() && !step.equals(claim)) {
            String joiner = endSentence(claim);
            sb.append(joiner).append(' ').append(joiner.isEmpty() ? step : capitalize(step));
        }
        sb.append(endSentence(sb.toString())).append("\n\n");
    }

    private static void appendBody(StringBuilder sb, ValidatedObservation f, Rendering rendering) {
        ComposedNote note = rendering.noteFor(f);
        // Evidence rationale is an audit explanation, not developer-facing advice.
        if (note != null) {
            appendStudentText(sb, note.nextStep());
        }
    }

    private static boolean containsGraderMechanics(@Nullable String text) {
        return DeveloperTextSanitizer.isGradingMeta(text);
    }

    private static void appendStudentText(StringBuilder sb, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String clean = sanitizeStudentText(text);
        if (!clean.isBlank()) {
            sb.append(clean).append("\n\n");
        }
    }

    private static final Map<String, String> EXT_TO_LANG = Map.ofEntries(
            Map.entry("swift", "swift"),
            Map.entry("kt", "kotlin"),
            Map.entry("kts", "kotlin"),
            Map.entry("java", "java"),
            Map.entry("py", "python"),
            Map.entry("js", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "tsx"),
            Map.entry("jsx", "jsx"),
            Map.entry("rb", "ruby"),
            Map.entry("go", "go"),
            Map.entry("rs", "rust"),
            Map.entry("c", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("h", "c"),
            Map.entry("hpp", "cpp"),
            Map.entry("cs", "csharp"),
            Map.entry("xml", "xml"),
            Map.entry("json", "json"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("md", "markdown"),
            Map.entry("sh", "bash"),
            Map.entry("html", "html"),
            Map.entry("css", "css"));

    private static String detectLanguage(ValidatedObservation f) {
        String location = extractPrimaryLocation(f);
        if (location == null) return "";
        String path = location.contains(":") ? location.substring(0, location.lastIndexOf(':')) : location;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "";
        String ext = path.substring(dot + 1).toLowerCase();
        return EXT_TO_LANG.getOrDefault(ext, "");
    }

    private static String severityEmoji(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "\uD83D\uDD34"; // 🔴
            case MAJOR -> "\uD83D\uDFE0"; // 🟠
            case MINOR -> "\uD83D\uDFE1"; // 🟡
            case INFO -> "\u2139\uFE0F"; // ℹ️
        };
    }

    private static Severity severity(ValidatedObservation observation) {
        return Objects.requireNonNull(observation.severity(), "A problem observation must have a severity");
    }

    @Nullable
    private static String extractPrimaryLocation(ValidatedObservation f) {
        JsonNode evidence = f.evidence();
        if (evidence == null || evidence.isNull()) return null;
        JsonNode citations = evidence.get("citations");
        if (citations == null || !citations.isArray() || citations.isEmpty()) return null;
        JsonNode first = citations.get(0);
        if (!first.isObject()) return null;
        String sourceKind = first.path("sourceKind").asString();
        if (!sourceKind.equals("scm.pull-request.diff") && !sourceKind.equals("scm.repository.tree")) return null;
        JsonNode pathNode = first.get("path");
        if (pathNode == null || !pathNode.isString()) return null;
        String path = repoRelative(pathNode.asString());
        JsonNode startLineNode = first.get("startLine");
        if (startLineNode != null && startLineNode.isNumber()) {
            return path + ":" + startLineNode.asInt();
        }
        return path;
    }

    @Nullable
    private static String extractPrimarySnippet(ValidatedObservation f) {
        JsonNode evidence = f.evidence();
        if (evidence == null || evidence.isNull()) return null;
        JsonNode citations = evidence.get("citations");
        if (citations == null || !citations.isArray() || citations.isEmpty()) return null;
        JsonNode quote = citations.get(0).get("quote");
        if (quote == null || !quote.isString()) return null;
        String snippet = quote.asString();
        return (snippet != null && !snippet.isBlank()) ? snippet.strip() : null;
    }

    /** What landed on a line, and what has to fall back to the summary because it could not. */
    record PlacedNotes(List<DiffNote> notes, List<ValidatedObservation> unplaced) {}

    private static PlacedNotes collectDiffNotes(
            List<ValidatedObservation> negatives, Rendering rendering, GroundingContext grounding) {
        List<DiffNote> notes = new ArrayList<>();
        List<ValidatedObservation> unplaced = new ArrayList<>();

        for (ValidatedObservation f : negatives) {
            if (notes.size() >= PracticeDetectionResultParser.MAX_DELIVERY_DIFF_NOTES) {
                unplaced.add(f);
                continue;
            }

            JsonNode evidence = f.evidence();
            if (evidence == null || evidence.isNull()) {
                unplaced.add(f);
                continue;
            }
            JsonNode citations = evidence.get("citations");
            if (citations == null || !citations.isArray() || citations.isEmpty()) {
                unplaced.add(f);
                continue;
            }

            ComposedNote composed = rendering.noteFor(f);
            ComposedFeedbackUnit.ResolvedAnchor selected = composed == null
                            || composed.placement().kind() != ComposedFeedbackUnit.InContextPlacement.PlacementKind.DIFF
                    ? null
                    : composed.placement().diffAnchor();
            int citationIndex = selected == null ? 0 : selected.citationIndex();
            if (citationIndex < 0 || citationIndex >= citations.size()) {
                unplaced.add(f);
                continue;
            }
            JsonNode citation = citations.get(citationIndex);
            if (!citation.isObject()) {
                unplaced.add(f);
                continue;
            }
            String path = selected == null ? citation.path("path").asString(null) : selected.path();
            int startLine = selected == null ? citation.path("startLine").asInt(0) : selected.startLine();
            if (path == null) {
                unplaced.add(f);
                continue;
            }
            if (startLine <= 0) {
                unplaced.add(f);
                continue;
            }

            String snippet = citation.path("quote").asString(null);
            if (!grounding.anchorIsGrounded(path, snippet)) {
                unplaced.add(f);
                continue;
            }

            Integer endLine =
                    selected == null ? integerAtLeast(citation.get("endLine"), startLine) : selected.endLine();

            String body = composeDiffNoteBody(f, rendering);
            if (body != null && !body.isBlank()) {
                notes.add(new DiffNote(repoRelative(path), startLine, endLine, body, f.recurrenceKey()));
            } else {
                unplaced.add(f);
            }
        }

        return new PlacedNotes(notes, unplaced);
    }

    private static @Nullable Integer integerAtLeast(@Nullable JsonNode value, int minimum) {
        return value != null && value.isNumber() && value.asInt() >= minimum ? value.asInt() : null;
    }

    private static @Nullable String composeDiffNoteBody(ValidatedObservation f, Rendering rendering) {
        var words = new StringBuilder();
        appendBody(words, f, rendering);
        var sb = new StringBuilder();
        appendObservationHeader(sb, f, false, rendering);
        sb.append("\n\n").append(words);

        String body = sb.toString().strip();
        if (body.length() > MAX_DIFF_NOTE_BODY_LENGTH) {
            body = clampToSentenceBudget(body, MAX_DIFF_NOTE_BODY_LENGTH);
        }
        return body.isBlank() ? null : body;
    }

    /** A fenced block ends on the first fence at least as long, so the wrapper has to outrun the quote. */
    static String fenceFor(String snippet) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < snippet.length(); i++) {
            run = snippet.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    record Rendering(
            Map<String, String> whyBySlug,
            Set<String> emittedWhy,
            ComposedNotes notes,
            @Nullable String lead) {
        @Nullable
        ComposedNote noteFor(ValidatedObservation f) {
            return notes.byObservation().get(f);
        }
    }

    record ComposedNotes(Map<ValidatedObservation, ComposedNote> byObservation) {
        static ComposedNotes none() {
            return new ComposedNotes(Map.of());
        }

        static ComposedNotes claim(List<ValidatedObservation> ordered, List<ComposedFeedbackUnit> units) {
            if (units.isEmpty()) {
                return none();
            }
            Map<String, ComposedNote> unclaimed = new HashMap<>();
            for (ComposedFeedbackUnit unit : units) {
                if (unit.channel() != FeedbackChannel.IN_CONTEXT
                        || unit.action() == ComposedFeedbackUnit.Action.WITHHOLD) {
                    continue;
                }
                ComposedNote note = ComposedNote.of(unit);
                if (note != null) {
                    unclaimed.putIfAbsent(unit.practiceSlug(), note);
                }
            }
            Map<ValidatedObservation, ComposedNote> byObservation = new IdentityHashMap<>();
            for (ValidatedObservation f : ordered) {
                ComposedNote note = unclaimed.remove(f.practiceSlug());
                if (note != null) {
                    byObservation.put(f, note);
                }
            }
            return new ComposedNotes(byObservation);
        }
    }

    record ComposedNote(String title, String nextStep, ComposedFeedbackUnit.InContextPlacement placement) {
        @Nullable
        static ComposedNote of(ComposedFeedbackUnit unit) {
            String title = clamp(sanitizeStudentText(unit.title()), ComposedFeedbackUnit.MAX_TITLE_LENGTH);
            String nextStep = clamp(sanitizeStudentText(unit.nextStep()), ComposedFeedbackUnit.MAX_NEXT_STEP_LENGTH);
            return title.isBlank() || nextStep.isBlank() || unit.placement() == null
                    ? null
                    : new ComposedNote(title, nextStep, unit.placement());
        }
    }

    private static String clamp(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength).strip();
    }

    /** Server-derived diff content used to validate model-selected inline anchors. */
    record GroundingContext(boolean active, boolean forceNoLocus, Map<String, String> hunkByFile) {
        static GroundingContext none() {
            return new GroundingContext(false, false, Map.of());
        }

        static GroundingContext fromDiff(ArtifactKind artifact, @Nullable String unifiedDiff) {
            if (ArtifactKinds.ISSUE.equals(artifact)) {
                return new GroundingContext(true, true, Map.of());
            }
            if (unifiedDiff == null || unifiedDiff.isBlank()) {
                return none();
            }
            return new GroundingContext(true, false, parseHunksByFile(unifiedDiff));
        }

        boolean anchorIsGrounded(@Nullable String path, @Nullable String snippet) {
            if (!active) return true;
            if (forceNoLocus) return false;
            if (path == null || path.isBlank()) return false;
            String key = repoRelative(path);
            String hunk = hunkByFile.get(key);
            if (hunk == null) {
                return false;
            }
            if (snippet == null || snippet.isBlank()) {
                return true;
            }
            return hunk.contains(normalizeForMatch(snippet));
        }

        private static Map<String, String> parseHunksByFile(String diff) {
            Map<String, StringBuilder> acc = new HashMap<>();
            String currentFile = null;
            for (String raw : diff.split("\n", -1)) {
                String line = raw;
                if (line.startsWith("[L") && line.contains("] ")) {
                    line = line.substring(line.indexOf("] ") + 2);
                }
                if (line.startsWith("diff --git")) {
                    int bIdx = line.lastIndexOf(" b/");
                    currentFile = bIdx > 0 ? line.substring(bIdx + 3) : null;
                    if (currentFile != null) acc.putIfAbsent(currentFile, new StringBuilder());
                    continue;
                }
                if (currentFile == null) continue;
                // New-side only: skip hunk headers, file markers, and deletions.
                if (line.startsWith("@@") || line.startsWith("+++") || line.startsWith("---")) continue;
                if (line.startsWith("+") || line.startsWith(" ")) {
                    acc.computeIfAbsent(currentFile, ignored -> new StringBuilder())
                            .append(normalizeForMatch(line.substring(1)))
                            .append('\n');
                }
            }
            Map<String, String> out = new HashMap<>(acc.size());
            for (Map.Entry<String, StringBuilder> e : acc.entrySet()) {
                out.put(e.getKey(), e.getValue().toString());
            }
            return out;
        }

        private static String normalizeForMatch(String s) {
            return s.replaceAll("\\s+", " ").strip();
        }
    }
}
