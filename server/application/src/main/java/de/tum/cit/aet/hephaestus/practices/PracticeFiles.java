package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.DefinitionSlugs;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * A practice as a folder of files: {@code practice.md} holds YAML front matter and the criteria as its body, and
 * {@code guide.md}, {@code figures/<name>.svg}, {@code visual.svg} and {@code script.ts} hold the rest. The files are a
 * serialization of {@link PracticeDefinition}, not a second model: reading checks a folder with the validator of a
 * save in the web app (ADR 0118 decision 1).
 */
@Component
public class PracticeFiles {
    public static final String FORMAT = "hephaestus.practice/1";
    public static final String PRACTICE = "practice.md";
    public static final String GUIDE = "guide.md";
    public static final String VISUAL = "visual.svg";
    public static final String SCRIPT = "script.ts";
    private static final String FIGURES = "figures/";
    private static final int MAX_FILE_BYTES = 128 * 1024;
    private static final int MAX_FOLDER_BYTES = 256 * 1024;
    private static final Pattern FIGURE = PracticeGuidanceRules.FIGURE_SOURCE;
    private static final Set<String> KEYS = Set.of(
            "format",
            "name",
            "kind",
            "group",
            "reviewOn",
            "about",
            "appliesWhen",
            "reads",
            "reviewedBy",
            "lineNotes",
            "scan",
            "guidance");
    private static final String UNKNOWN_FIELD = "Unknown field: ";
    private static final Set<String> REQUIRED_KEYS =
            Set.of("format", "name", "kind", "reviewOn", "about", "reads", "reviewedBy");
    private static final Set<String> GUIDANCE_KEYS =
            Set.of("whyItMatters", "whatGoodLooksLike", "goingWell", "visualAlt");

    private final JsonMapper json;
    private final PracticeDefinitionValidator validator;
    private final YAMLMapper yaml;

    public PracticeFiles(JsonMapper json, PracticeDefinitionValidator validator) {
        this.json = json;
        this.validator = validator;
        // The tree reader refuses a duplicate key, and the location pass refuses an alias.
        var settings = LoadSettings.builder()
                .setAllowDuplicateKeys(false)
                .setCodePointLimit(MAX_FILE_BYTES)
                .build();
        this.yaml = YAMLMapper.builder(YAMLFactory.builder()
                        .loadSettings(settings)
                        .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                        .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                        .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
                        // A text such as "1e3" or ".inf" must not read back as a number.
                        .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                        .build())
                .build();
    }

    /** A folder that parsed and passed validation, or the problems that stop it. Exactly one is present. */
    public record Read(@Nullable CatalogPracticeDocument document, List<PracticeFileProblemDTO> problems) {
        public Read {
            problems = List.copyOf(problems);
            if ((document == null) == problems.isEmpty())
                throw new IllegalArgumentException("A read has a document or problems, never both.");
        }
    }

    /** The files of one practice folder, by path relative to the folder. */
    public Map<String, String> write(CatalogPracticeDocument document) {
        var definition = document.definition();
        ObjectNode tree = json.valueToTree(definition);
        var front = json.createObjectNode();
        front.put("format", FORMAT);
        front.put("name", definition.name());
        front.put("kind", document.kind().value());
        if (document.groupSlug() != null) front.put("group", document.groupSlug());
        ObjectNode reviewOn = (ObjectNode) tree.get("reviewOn");
        if (definition.reviewOn().state().isEmpty()) reviewOn.remove("state");
        front.set("reviewOn", reviewOn);
        front.set("about", tree.get("about"));
        if (definition.appliesWhen() != null) front.set("appliesWhen", tree.get("appliesWhen"));
        front.set("reads", tree.get("reads"));
        ObjectNode reviewedBy = (ObjectNode) tree.get("reviewedBy");
        if (definition.reviewedBy().humanReason() == null) reviewedBy.remove("humanReason");
        if (definition.reviewedBy().limits().isEmpty()) reviewedBy.remove("limits");
        front.set("reviewedBy", reviewedBy);
        if (definition.lineNotes()) front.put("lineNotes", true);
        if (definition.scan() != null) front.set("scan", tree.get("scan"));
        var guidance = definition.guidance();
        var guidanceNode = json.createObjectNode();
        if (guidance.whyItMatters() != null) guidanceNode.put("whyItMatters", guidance.whyItMatters());
        if (guidance.whatGoodLooksLike() != null) guidanceNode.put("whatGoodLooksLike", guidance.whatGoodLooksLike());
        if (guidance.goingWell() != null) guidanceNode.put("goingWell", guidance.goingWell());
        if (guidance.visual() != null)
            guidanceNode.put("visualAlt", guidance.visual().alt());
        if (!guidanceNode.isEmpty()) front.set("guidance", guidanceNode);

        var files = new TreeMap<String, String>();
        files.put(PRACTICE, "---\n" + yaml.writeValueAsString(front) + "---\n" + definition.criteria() + "\n");
        if (guidance.visual() != null) files.put(VISUAL, guidance.visual().svg());
        if (guidance.guide() != null) {
            files.put(GUIDE, guidance.guide().markdown());
            guidance.guide().figures().forEach((name, svg) -> files.put(FIGURES + name + ".svg", svg));
        }
        if (definition.script() != null) files.put(SCRIPT, definition.script());
        return files;
    }

    /** Parses and validates one folder. {@code slug} is the folder's name. */
    public Read read(String slug, Map<String, String> files) {
        var problems = new ArrayList<PracticeFileProblemDTO>();
        if (slug.length() > DefinitionSlugs.MAX_LENGTH || !slug.matches(DefinitionSlugs.PRACTICE_PATTERN))
            problems.add(problem(
                    PRACTICE,
                    PracticeFileProblemDTO.SLUG_INVALID,
                    "Name the folder with a lowercase slug with single hyphens."));
        long total = 0;
        var normalized = new HashMap<String, String>();
        for (var file : files.entrySet()) {
            var path = file.getKey();
            int bytes = file.getValue().getBytes(StandardCharsets.UTF_8).length;
            total += bytes;
            if (!isKnown(path))
                problems.add(problem(
                        path,
                        PracticeFileProblemDTO.UNKNOWN_FILE,
                        "A practice folder holds only " + PRACTICE + ", " + GUIDE + ", " + VISUAL + ", " + SCRIPT
                                + " and figures/<name>.svg."));
            else if (bytes > MAX_FILE_BYTES)
                problems.add(problem(
                        path,
                        PracticeFileProblemDTO.FILE_TOO_LARGE,
                        "Keep each file under " + MAX_FILE_BYTES / 1024 + " KiB."));
            else normalized.put(path, file.getValue().replace("\r\n", "\n"));
        }
        if (total > MAX_FOLDER_BYTES)
            problems.add(problem(
                    PRACTICE,
                    PracticeFileProblemDTO.FOLDER_TOO_LARGE,
                    "Keep a practice folder under " + MAX_FOLDER_BYTES / 1024 + " KiB."));
        var source = normalized.get(PRACTICE);
        if (source == null) {
            if (!files.containsKey(PRACTICE))
                problems.add(problem(
                        PRACTICE,
                        PracticeFileProblemDTO.PRACTICE_MISSING,
                        "A practice folder needs " + PRACTICE + "."));
            return new Read(null, problems);
        }
        if (!problems.isEmpty()) return new Read(null, problems);
        return parse(source, normalized);
    }

    private Read parse(String source, Map<String, String> files) {
        if (!source.startsWith("---\n"))
            return refused(
                    PRACTICE,
                    1,
                    1,
                    PracticeFileProblemDTO.FRONT_MATTER_MISSING,
                    "Start " + PRACTICE + " with a line ---.");
        int end = source.indexOf("\n---\n", 3);
        if (end < 0)
            return refused(
                    PRACTICE,
                    null,
                    null,
                    PracticeFileProblemDTO.FRONT_MATTER_MISSING,
                    "Close the front matter with a line ---.");
        String header = source.substring(4, end + 1);
        String body = source.substring(end + 5);
        String criteria = body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
        JsonNode parsed;
        try {
            parsed = yaml.readTree(header);
        } catch (JacksonException exception) {
            var location = exception.getLocation();
            return refused(
                    PRACTICE,
                    location == null ? null : location.getLineNr() + 1,
                    location == null ? null : location.getColumnNr(),
                    PracticeFileProblemDTO.YAML_INVALID,
                    "The front matter is not valid YAML. Check the indentation, quotes and brackets near this line.");
        }
        var locations = new Locations(header);
        if (locations.alias() != null)
            return new Read(
                    null,
                    List.of(locations.at(
                            Objects.requireNonNull(locations.alias()),
                            PracticeFileProblemDTO.YAML_INVALID,
                            "Write the value out. A practice file uses no YAML alias.")));
        if (!(parsed instanceof ObjectNode front))
            return refused(
                    PRACTICE,
                    2,
                    1,
                    PracticeFileProblemDTO.FRONT_MATTER_INVALID,
                    "Write the front matter as YAML keys and values.");
        var problems = new ArrayList<PracticeFileProblemDTO>();
        for (var key : front.propertyNames())
            if (!KEYS.contains(key))
                problems.add(
                        locations.at("/" + key, PracticeFileProblemDTO.UNKNOWN_KEY, "Remove the key " + key + "."));
        for (var key : REQUIRED_KEYS)
            if (!front.has(key))
                problems.add(new PracticeFileProblemDTO(
                        PRACTICE, 2, 1, PracticeFileProblemDTO.KEY_MISSING, "Add the key " + key + "."));
        if (front.has("guidance") && !(front.get("guidance") instanceof ObjectNode))
            problems.add(locations.at(
                    "/guidance", PracticeFileProblemDTO.FIELD_INVALID, "Write guidance as keys and values."));
        if (front.path("guidance") instanceof ObjectNode guidance)
            for (var key : guidance.propertyNames())
                if (!GUIDANCE_KEYS.contains(key))
                    problems.add(locations.at(
                            "/guidance/" + key,
                            PracticeFileProblemDTO.UNKNOWN_KEY,
                            "Remove the key guidance." + key + "."));
        if (front.has("format") && !FORMAT.equals(text(front.path("format"))))
            problems.add(locations.at(
                    "/format",
                    PracticeFileProblemDTO.FORMAT_UNSUPPORTED,
                    "Set format to " + FORMAT + ". This server reads no other format."));
        var kindValue = text(front.path("kind"));
        ItemKind kind = null;
        try {
            if (kindValue != null) kind = new ItemKind(kindValue);
        } catch (IllegalArgumentException invalid) {
            kind = null;
        }
        if (front.has("kind") && kind == null)
            problems.add(locations.at(
                    "/kind",
                    PracticeFileProblemDTO.FIELD_INVALID,
                    "Set kind to the item kind that the practice reviews, such as scm.pull_request."));
        var group = text(front.path("group"));
        if (front.has("group") && (group == null || !group.matches(DefinitionSlugs.DEFINITION_PATTERN)))
            problems.add(locations.at(
                    "/group", PracticeFileProblemDTO.FIELD_INVALID, "Set group to the slug of a practice group."));
        boolean hasVisual = files.containsKey(VISUAL);
        boolean hasAlt = front.path("guidance").has("visualAlt");
        if (hasVisual != hasAlt)
            problems.add(
                    hasVisual
                            ? problem(
                                    VISUAL,
                                    PracticeFileProblemDTO.VISUAL_ALT_MISSING,
                                    "Describe " + VISUAL + " in guidance.visualAlt.")
                            : locations.at(
                                    "/guidance/visualAlt",
                                    PracticeFileProblemDTO.VISUAL_MISSING,
                                    "Add " + VISUAL + " or remove visualAlt."));
        if (!problems.isEmpty()) return new Read(null, problems);

        var definition = definitionNode(front, criteria, files);
        PracticeDefinition authored;
        try {
            authored = json.readerFor(PracticeDefinition.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(definition);
        } catch (DatabindException exception) {
            var pointer = pointer(exception.getPath());
            var name = pointer.isEmpty()
                    ? "the front matter"
                    : pointer.substring(1).replace('/', '.');
            var cause = exception.getCause();
            if (exception instanceof UnrecognizedPropertyException unknown)
                return new Read(
                        null,
                        List.of(locations.at(
                                pointer,
                                PracticeFileProblemDTO.UNKNOWN_KEY,
                                "Remove the key " + unknown.getPropertyName() + ".")));
            // ClosedPracticeInput refuses an unknown nested key from its any-setter, with the key in its message.
            var reason = cause instanceof IllegalArgumentException invalid ? invalid.getMessage() : null;
            if (reason != null && reason.startsWith(UNKNOWN_FIELD))
                return new Read(
                        null,
                        List.of(locations.at(
                                pointer,
                                PracticeFileProblemDTO.UNKNOWN_KEY,
                                "Remove the key " + reason.substring(UNKNOWN_FIELD.length()) + ".")));
            var message = reason != null
                    ? reason
                    : "Check the value of " + name + ". It does not have the form that the practice editor saves.";
            return new Read(null, List.of(locations.at(pointer, PracticeFileProblemDTO.FIELD_INVALID, message)));
        }
        var guidanceProblem = guidanceProblem(authored.guidance());
        if (guidanceProblem != null) return new Read(null, List.of(guidanceProblem));
        try {
            validator.validate(Objects.requireNonNull(kind), authored);
        } catch (IllegalArgumentException invalid) {
            return refused(
                    PRACTICE,
                    null,
                    null,
                    PracticeFileProblemDTO.DEFINITION_INVALID,
                    Objects.toString(invalid.getMessage(), ""));
        }
        var unused = files.keySet().stream()
                .filter(path -> FIGURE.matcher(path).matches())
                .filter(path -> authored.guidance().guide() == null
                        || !authored.guidance().guide().figures().containsKey(figureName(path)))
                .map(path -> problem(
                        path, PracticeFileProblemDTO.FIGURE_UNUSED, "Show this figure in " + GUIDE + ", or remove it."))
                .toList();
        if (!unused.isEmpty()) return new Read(null, unused);
        return new Read(new CatalogPracticeDocument(Objects.requireNonNull(kind), group, authored), List.of());
    }

    /** A visual or a guide that the validator would refuse, placed on its own file. */
    private static @Nullable PracticeFileProblemDTO guidanceProblem(PracticeGuidance guidance) {
        try {
            if (guidance.visual() != null) PracticeGuidanceRules.validate(guidance.visual());
        } catch (IllegalArgumentException invalid) {
            return problem(VISUAL, PracticeFileProblemDTO.FILE_INVALID, Objects.toString(invalid.getMessage(), ""));
        }
        if (guidance.guide() != null)
            for (var figure : guidance.guide().figures().entrySet()) {
                try {
                    PracticeGuidanceRules.validateSvg("The figure “" + figure.getKey() + "”", figure.getValue());
                } catch (IllegalArgumentException invalid) {
                    return problem(
                            FIGURES + figure.getKey() + ".svg",
                            PracticeFileProblemDTO.FILE_INVALID,
                            Objects.toString(invalid.getMessage(), ""));
                }
            }
        try {
            if (guidance.guide() != null) PracticeGuidanceRules.validate(guidance.guide());
        } catch (IllegalArgumentException invalid) {
            return problem(GUIDE, PracticeFileProblemDTO.FILE_INVALID, Objects.toString(invalid.getMessage(), ""));
        }
        return null;
    }

    private ObjectNode definitionNode(ObjectNode front, String criteria, Map<String, String> files) {
        var definition = json.createObjectNode();
        definition.set("name", front.get("name"));
        var reviewOn = front.get("reviewOn");
        if (reviewOn instanceof ObjectNode on && !on.has("state")) on.putObject("state");
        definition.set("reviewOn", reviewOn);
        definition.set(
                "appliesWhen",
                front.has("appliesWhen") ? front.get("appliesWhen") : JsonNodeFactory.instance.nullNode());
        definition.set("about", front.get("about"));
        definition.set("reads", front.get("reads"));
        var reviewedBy = front.get("reviewedBy");
        if (reviewedBy instanceof ObjectNode by) {
            if (!by.has("humanReason")) by.putNull("humanReason");
            if (!by.has("limits")) by.putArray("limits");
        }
        definition.set("reviewedBy", reviewedBy);
        definition.put("criteria", criteria);
        definition.put("script", files.get(SCRIPT));
        definition.set("scan", front.has("scan") ? front.get("scan") : JsonNodeFactory.instance.nullNode());
        definition.set(
                "lineNotes",
                front.has("lineNotes") ? front.get("lineNotes") : JsonNodeFactory.instance.booleanNode(false));
        var authored = front.path("guidance");
        var guidance = definition.putObject("guidance");
        for (var key : List.of("whyItMatters", "whatGoodLooksLike", "goingWell"))
            guidance.set(key, authored.has(key) ? authored.get(key) : JsonNodeFactory.instance.nullNode());
        var visual = files.get(VISUAL);
        if (visual == null) guidance.putNull("visual");
        else guidance.putObject("visual").put("svg", visual).set("alt", authored.get("visualAlt"));
        var guide = files.get(GUIDE);
        if (guide == null) guidance.putNull("guide");
        else {
            var node = guidance.putObject("guide");
            node.put("markdown", guide);
            var figures = node.putObject("figures");
            for (var name : PracticeGuidanceRules.figureNames(guide)) {
                var svg = files.get(FIGURES + name + ".svg");
                if (svg != null) figures.put(name, svg);
            }
        }
        return definition;
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static boolean isKnown(String path) {
        return PRACTICE.equals(path)
                || GUIDE.equals(path)
                || VISUAL.equals(path)
                || SCRIPT.equals(path)
                || FIGURE.matcher(path).matches();
    }

    private static String figureName(String path) {
        var matcher = FIGURE.matcher(path);
        if (!matcher.matches()) throw new IllegalArgumentException("Not a figure path: " + path);
        return matcher.group(1);
    }

    private static String pointer(List<JacksonException.Reference> path) {
        var pointer = new StringBuilder();
        for (var reference : path) {
            pointer.append('/');
            pointer.append(reference.getPropertyName() != null ? reference.getPropertyName() : reference.getIndex());
        }
        return pointer.toString();
    }

    private static PracticeFileProblemDTO problem(String file, String code, String message) {
        return new PracticeFileProblemDTO(file, null, null, code, message);
    }

    private static Read refused(
            String file, @Nullable Integer line, @Nullable Integer column, String code, String message) {
        return new Read(null, List.of(new PracticeFileProblemDTO(file, line, column, code, message)));
    }

    /** Where each JSON pointer of the front matter starts in {@code practice.md}, to place a problem on its line. */
    private final class Locations {
        private final Map<String, int[]> starts = new LinkedHashMap<>();
        private @Nullable String alias;

        Locations(String header) {
            // Built only for a header that readTree parsed, so the token stream reads to its end.
            try (var parser = yaml.createParser(header)) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.END_OBJECT || parser.currentToken() == JsonToken.END_ARRAY)
                        continue;
                    var location = parser.currentTokenLocation();
                    if (alias == null && parser instanceof YAMLParser yamlParser && yamlParser.isCurrentAlias())
                        alias = parser.streamReadContext().pathAsPointer().toString();
                    // Line 1 of practice.md is the opening ---, so the header's first line is line 2.
                    starts.putIfAbsent(
                            parser.streamReadContext().pathAsPointer().toString(),
                            new int[] {location.getLineNr() + 1, location.getColumnNr()});
                }
            }
        }

        @Nullable
        String alias() {
            return alias;
        }

        PracticeFileProblemDTO at(String pointer, String code, String message) {
            for (var candidate = pointer; ; candidate = candidate.substring(0, candidate.lastIndexOf('/'))) {
                var start = starts.get(candidate);
                if (start != null) return new PracticeFileProblemDTO(PRACTICE, start[0], start[1], code, message);
                if (candidate.isEmpty()) return new PracticeFileProblemDTO(PRACTICE, null, null, code, message);
            }
        }
    }
}
