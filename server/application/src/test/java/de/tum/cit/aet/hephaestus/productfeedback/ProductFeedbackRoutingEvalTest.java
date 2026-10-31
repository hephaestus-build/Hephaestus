package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The unit tier of the routing evaluation (scripts/product-feedback-eval/). Every case runs through the production
 * routing and rendering code with the most permissive model answer the gate can receive. What code alone must keep
 * private stays private, and no case's forbidden text reaches an issue, whatever the model says. The run also writes
 * the report that {@code node scripts/product-feedback-eval.ts --report …} scores.
 */
@Tag("unit")
class ProductFeedbackRoutingEvalTest {
    private static final Path CASES = Path.of("../../scripts/product-feedback-eval/cases.json");
    private static final Path REPORT = Path.of("build/product-feedback-eval/unit-report.json");
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldKeepEveryCodeDecidedCaseOffThePublicRouteWhenTheModelAllowsEverything() throws Exception {
        byte[] bytes = Files.readAllBytes(CASES);
        JsonNode corpus = mapper.readTree(bytes);
        String repository = corpus.path("snapshot").path("repository").asString();
        long poisoned = poisonedIssue(corpus);
        Redaction.Identities identities = identities(corpus.path("identities"));
        ArrayNode results = mapper.createArrayNode();
        var failures = new ArrayList<String>();
        for (JsonNode item : corpus.path("cases")) {
            String id = item.path("id").asString();
            var kind = ProductFeedback.Kind.valueOf(item.path("kind").asString());
            String message = item.path("message").asString();
            var outcome = RoutingGate.evaluate(
                    item.path("allowPublic").asBoolean(),
                    kind,
                    true,
                    true,
                    message,
                    identities,
                    repository,
                    permissiveDraft(kind, message, poisoned),
                    false);
            boolean isPublic = outcome.decision().route() == FilingRoute.PUBLIC;
            var rendered = IssueRenderer.render(
                    outcome.draft() == null
                            ? Fallback.of(kind, outcome.original().text())
                            : outcome.draft(),
                    new IssueRenderer.Context(
                            "1.0.0-test", item.path("context").path("route").asString(null), null),
                    repository,
                    isPublic ? null : outcome.decision().reason().words(),
                    isPublic ? null : "report-" + id,
                    null,
                    "marker-" + id,
                    "1.0.0-test");
            String route = outcome.decision().route().name().toLowerCase(Locale.ROOT);
            String reason = outcome.decision().reason().tag();
            ObjectNode result = results.addObject()
                    .put("id", id)
                    .put("route", route)
                    .put("reason", reason)
                    .put("title", rendered.title())
                    .put("body", rendered.body());
            if (poisoned == 0) result.putNull("relatedIssue");
            else result.put("relatedIssue", poisoned);
            result.putArray("writes")
                    .addObject()
                    .put("kind", "create-issue")
                    .put("repository", route)
                    .putNull("issue");
            JsonNode expected = item.path("expected");
            String decidedBy = expected.path("decidedBy").asString();
            String expectedRoute = expected.path("route").asString();
            if (!"model".equals(decidedBy) && !expectedRoute.equals(route))
                failures.add(id + ": expected " + expectedRoute + ", routed " + route + " (" + reason + ")");
            if ("code".equals(decidedBy) && !expected.path("reason").asString().equals(reason))
                failures.add(id + ": expected reason " + expected.path("reason").asString() + ", got " + reason);
            String text = (rendered.title() + "\n" + rendered.body()).toLowerCase(Locale.ROOT);
            for (JsonNode secret : item.path("mustNotAppear"))
                if (text.contains(secret.asString().toLowerCase(Locale.ROOT)))
                    failures.add(id + ": \"" + secret.asString() + "\" reached the rendered issue");
        }
        writeReport(bytes, results);
        assertThat(failures).isEmpty();
    }

    /** The answer that tries hardest to make a report public: an issue, high confidence, safe, naming the poisoned issue. */
    private static Draft permissiveDraft(ProductFeedback.Kind kind, String message, long related) {
        boolean bug = kind != ProductFeedback.Kind.IDEA;
        String title = message.lines().findFirst().orElse("Report");
        return new Draft(
                Draft.Disposition.ISSUE,
                bug ? Draft.Type.BUG : Draft.Type.FEATURE,
                title.length() <= Draft.TITLE_MAX ? title : title.substring(0, Draft.TITLE_MAX),
                bug ? message : "",
                "",
                List.of(),
                bug ? "" : message,
                "",
                List.of(),
                "",
                related == 0 ? null : related,
                false,
                false,
                true,
                Draft.Confidence.HIGH);
    }

    private static long poisonedIssue(JsonNode corpus) {
        for (JsonNode issue : corpus.path("snapshot").path("openIssues"))
            if (issue.path("poisoned").asBoolean(false))
                return issue.path("number").asLong();
        return 0;
    }

    private static Redaction.Identities identities(JsonNode node) {
        var names = new ArrayList<String>();
        JsonNode reporter = node.path("reporter");
        names.add(reporter.path("name").asString());
        names.add(reporter.path("login").asString());
        String email = reporter.path("email").asString();
        if (email.indexOf('@') > 0) names.add(email.substring(0, email.indexOf('@')));
        for (JsonNode member : node.path("members")) names.add(member.asString());
        var workspaces = new ArrayList<String>();
        for (JsonNode workspace : node.path("workspaces")) {
            workspaces.add(workspace.path("name").asString());
            workspaces.add(workspace.path("slug").asString());
        }
        return new Redaction.Identities(names, workspaces);
    }

    private void writeReport(byte[] cases, ArrayNode results) throws Exception {
        ObjectNode report = mapper.createObjectNode()
                .put("version", 1)
                .put("tier", "unit")
                .put(
                        "casesSha256",
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(cases)));
        report.putNull("model");
        report.putNull("promptSha256");
        report.set("results", results);
        Files.createDirectories(REPORT.toAbsolutePath().getParent());
        Files.writeString(REPORT, mapper.writeValueAsString(report), StandardCharsets.UTF_8);
    }
}
