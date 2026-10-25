package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionInputDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

public final class DefinitionTestFixtures {
    private DefinitionTestFixtures() {}

    public static PracticeDefinitionInputDTO authoring(PracticeDefinition definition) {
        return JsonMapper.builder().build().convertValue(definition, PracticeDefinitionInputDTO.class);
    }

    public static PracticeDefinition definition() {
        return definition("Describe the change", "Explain the change and its purpose.");
    }

    public static PracticeDefinition definition(String name, String criteria) {
        return new PracticeDefinition(
                name,
                new ReviewOn(List.of(new ItemEventName("scm.pull_request.opened")), Map.of()),
                null,
                AboutRole.AUTHOR,
                new PracticeReads(
                        "2.0.0",
                        List.of(new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED))),
                new ReviewedBy(ReviewedByKind.HEPHAESTUS, null, List.of()),
                criteria,
                null,
                null,
                true,
                new PracticeGuidance(
                        "Makes the change clear.",
                        "The description explains the purpose.",
                        "You explain each change.",
                        null,
                        null));
    }

    public static PracticeDefinition rename(PracticeDefinition original, String name) {
        return edit(original, name, original.criteria());
    }

    public static PracticeDefinition withCriteria(PracticeDefinition original, String criteria) {
        return edit(original, original.name(), criteria);
    }

    private static PracticeDefinition edit(PracticeDefinition original, String name, String criteria) {
        return new PracticeDefinition(
                name,
                original.reviewOn(),
                original.appliesWhen(),
                original.about(),
                original.reads(),
                original.reviewedBy(),
                criteria,
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
    }

    public static PracticeDefinition definition(ItemKind kind, String name, String criteria, @Nullable String script) {
        String event = switch (kind.value()) {
            case "docs.document" -> "published";
            case "chat.thread" -> "settled";
            default -> "opened";
        };
        String source = switch (kind.value()) {
            case "scm.pull_request", "scm.pull_request_review" -> "scm.pull-request.core";
            case "scm.issue" -> "scm.issue.core";
            case "chat.thread" -> "slack.conversation.thread";
            case "docs.document" -> "docs.document.core";
            case "scm.file", "scm.repository" -> "scm.repository.tree";
            default -> throw new IllegalArgumentException("Unsupported fixture kind");
        };
        return new PracticeDefinition(
                name,
                new ReviewOn(List.of(new ItemEventName(kind.value() + "." + event)), Map.of()),
                null,
                kind.value().equals("scm.file") || kind.value().equals("scm.repository") ? null : AboutRole.AUTHOR,
                new PracticeReads("2.0.0", List.of(new PracticeReadSource(new SourceKind(source), ReadNeed.REQUIRED))),
                new ReviewedBy(ReviewedByKind.HEPHAESTUS, null, List.of()),
                criteria,
                script,
                null,
                kind.value().equals("scm.pull_request"),
                new PracticeGuidance(null, null, null, null, null));
    }

    public static PracticeDefinition withAbout(PracticeDefinition original, @Nullable AboutRole about) {
        return new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                about,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
    }

    public static PracticeDefinition withReviewOn(PracticeDefinition original, ReviewOn reviewOn) {
        return new PracticeDefinition(
                original.name(),
                reviewOn,
                original.appliesWhen(),
                original.about(),
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
    }

    public static PracticeDefinition withLineNotes(PracticeDefinition original, boolean lineNotes) {
        return new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                original.about(),
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                lineNotes,
                original.guidance());
    }
}
