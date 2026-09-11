package de.tum.cit.aet.hephaestus.evidence.internal;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalog;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
import de.tum.cit.aet.hephaestus.evidence.CompletenessPolicy;
import de.tum.cit.aet.hephaestus.evidence.ErasurePolicy;
import de.tum.cit.aet.hephaestus.evidence.IdentityMode;
import de.tum.cit.aet.hephaestus.evidence.IdentityPolicy;
import de.tum.cit.aet.hephaestus.evidence.PrivacyClass;
import de.tum.cit.aet.hephaestus.evidence.RequiredCaptureQuality;
import de.tum.cit.aet.hephaestus.evidence.RetentionPolicy;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceState;
import de.tum.cit.aet.hephaestus.evidence.SourceAuthority;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUseBasis;
import de.tum.cit.aet.hephaestus.evidence.SourceUseDecision;
import de.tum.cit.aet.hephaestus.evidence.SourceUseOutcome;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ClasspathArtifactSourceCatalogRegistry implements ArtifactSourceCatalogRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClasspathArtifactSourceCatalogRegistry.class);

    static final SourceContractVersion CURRENT_VERSION = new SourceContractVersion("1.1.0");
    static final String CATALOG_RESOURCE = "contracts/artifact-source/1.1.0/catalog.json";
    static final String USE_DECISIONS_RESOURCE = "contracts/artifact-source/1.1.0/source-use-decisions.json";
    private final ArtifactSourceCatalog catalog;
    private final String catalogDigest;
    private final Map<String, SourceUseDecision> useDecisions;
    private final Clock clock;
    private final ArtifactSourceCatalog historicalCatalog;
    private final Map<String, SourceUseDecision> historicalUseDecisions;

    public ClasspathArtifactSourceCatalogRegistry(JsonMapper objectMapper, Clock clock) {
        this.clock = clock;
        byte[] catalogBytes = readBytes(CATALOG_RESOURCE);
        this.catalog = parse(read(objectMapper, catalogBytes, CATALOG_RESOURCE));
        this.catalogDigest = sha256(catalogBytes);
        this.useDecisions = parseUseDecisions(read(objectMapper, USE_DECISIONS_RESOURCE));
        validateUseDecisions(catalog, useDecisions);
        SourceContractVersion historicalVersion = new SourceContractVersion("1.0.0");
        this.historicalCatalog =
                parse(read(objectMapper, "contracts/artifact-source/1.0.0/catalog.json"), historicalVersion);
        this.historicalUseDecisions = parseUseDecisions(
                read(objectMapper, "contracts/artifact-source/1.0.0/source-use-decisions.json"), historicalVersion);
        validateUseDecisions(historicalCatalog, historicalUseDecisions);
        useDecisions.values().stream()
                .map(SourceUseDecision::expiresAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .ifPresent(expiry -> {
                    long days = ChronoUnit.DAYS.between(clock.instant(), expiry);
                    if (days <= 30) {
                        log.warn(
                                "Artifact-source governance approval expires in {} day(s) at {}; renew the versioned source-use decisions before that deadline",
                                days,
                                expiry);
                    }
                });
    }

    @Override
    public ArtifactSourceCatalog current() {
        return catalog;
    }

    @Override
    public String catalogDigest() {
        return catalogDigest;
    }

    @Override
    public ArtifactSourceContract requireSource(SourceContractVersion version, SourceKind kind) {
        ArtifactSourceContract contract = readableCatalog(version)
                .source(kind)
                .orElseThrow(() ->
                        new IllegalArgumentException("Unknown source kind for contract " + version + ": " + kind));
        return contract;
    }

    /**
     * A use is permitted when the shipped contract carries a reviewed, unexpired decision for exactly this
     * source and purpose.
     *
     * <p>The decision in {@code source-use-decisions.json} is the gate, and no runtime configuration waives
     * it: a source whose decision is missing, refused, or expired is never read. Within that boundary,
     * connecting an integration and enabling a practice is what authorizes the everyday reading the product
     * exists to do.
     */
    @Override
    public boolean isSourceUsePermitted(SourceContractVersion version, SourceKind kind, SourceUsePurpose purpose) {
        return requireSource(version, kind).useDecisionIds().stream()
                .map(id -> requireUseDecision(version, id))
                .anyMatch(decision -> decision.permitsAt(clock.instant(), purpose));
    }

    /** A definition is read against the contract it pins; which contract a new review runs under is the manifest's decision. */
    @Override
    public Set<SourceKind> requireSourcesFor(SourceContractVersion version, String artifactKind) {
        Set<SourceKind> sources = readableCatalog(version).sourcesFor(artifactKind);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "No evidence source in contract " + version + " applies to artifact kind: " + artifactKind);
        }
        return sources;
    }

    @Override
    public List<SourceKind> requireDefaultSourcesFor(SourceContractVersion version, String artifactKind) {
        requireSourcesFor(version, artifactKind);
        return readableCatalog(version).defaultSourcesFor(artifactKind);
    }

    @Override
    public SourceUseDecision requireUseDecision(SourceContractVersion version, String decisionId) {
        readableCatalog(version);
        SourceUseDecision decision =
                (version.equals(catalog.version()) ? useDecisions : historicalUseDecisions).get(decisionId);
        if (decision == null) {
            throw new IllegalArgumentException(
                    "Unknown source-use decision for contract " + version + ": " + decisionId);
        }
        return decision;
    }

    @Override
    public Optional<Instant> earliestUseDecisionExpiry(@Nullable SourceUsePurpose purpose) {
        return useDecisions.values().stream()
                .filter(decision -> purpose == null || decision.purpose() == purpose)
                .map(SourceUseDecision::expiresAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo);
    }

    private ArtifactSourceCatalog readableCatalog(SourceContractVersion version) {
        if (catalog.version().equals(version)) {
            return catalog;
        }
        if (historicalCatalog.version().equals(version)) {
            return historicalCatalog;
        }
        throw new IllegalArgumentException("Unsupported source contract version: " + version);
    }

    static ArtifactSourceCatalog parse(JsonNode root) {
        return parse(root, CURRENT_VERSION);
    }

    private static ArtifactSourceCatalog parse(JsonNode root, SourceContractVersion expectedVersion) {
        requireObject(root, "catalog");
        rejectUnknown(root, Set.of("version", "sources"), "catalog");
        SourceContractVersion version = new SourceContractVersion(requiredText(root, "version", "catalog"));
        if (!expectedVersion.equals(version)) {
            throw new IllegalStateException("Catalog resource has unexpected version: " + version);
        }

        List<ArtifactSourceContract> sources = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "sources", "catalog")) {
            sources.add(parseSource(node));
        }
        return new ArtifactSourceCatalog(version, sources);
    }

    private static ArtifactSourceContract parseSource(JsonNode node) {
        requireObject(node, "source");
        rejectUnknown(
                node,
                Set.of(
                        "kind",
                        "displayName",
                        "description",
                        "selectionScope",
                        "artifactKinds",
                        "defaultRequirement",
                        "authority",
                        "identity",
                        "completeness",
                        "requiredQuality",
                        "privacyClass",
                        "supportedAbsenceStates",
                        "retentionPolicy",
                        "erasurePolicy",
                        "useDecisionIds"),
                "source");
        SourceKind kind = new SourceKind(requiredText(node, "kind", "source"));
        JsonNode identity = requiredObject(node, "identity", kind.toString());
        rejectUnknown(identity, Set.of("mode"), "identity " + kind);
        IdentityMode identityMode =
                enumValue(IdentityMode.class, requiredText(identity, "mode", "identity " + kind), "identity mode");

        JsonNode completeness = requiredObject(node, "completeness", kind.toString());
        rejectUnknown(
                completeness, Set.of("supportsComplete", "supportsPartial", "supportsEmpty"), "completeness " + kind);

        return new ArtifactSourceContract(
                kind,
                requiredText(node, "displayName", kind.toString()),
                requiredText(node, "description", kind.toString()),
                requiredText(node, "selectionScope", kind.toString()),
                textSet(node, "artifactKinds", kind.toString()),
                requiredBoolean(node, "defaultRequirement", kind.toString()),
                enumValue(SourceAuthority.class, requiredText(node, "authority", kind.toString()), "authority"),
                new IdentityPolicy(identityMode),
                new CompletenessPolicy(
                        requiredBoolean(completeness, "supportsComplete", kind.toString()),
                        requiredBoolean(completeness, "supportsPartial", kind.toString()),
                        requiredBoolean(completeness, "supportsEmpty", kind.toString())),
                enumValue(
                        RequiredCaptureQuality.class,
                        requiredText(node, "requiredQuality", kind.toString()),
                        "required capture quality"),
                enumValue(PrivacyClass.class, requiredText(node, "privacyClass", kind.toString()), "privacy class"),
                enumSet(SourceAbsenceState.class, node, "supportedAbsenceStates", kind.toString()),
                enumValue(
                        RetentionPolicy.class,
                        requiredText(node, "retentionPolicy", kind.toString()),
                        "retention policy"),
                enumValue(ErasurePolicy.class, requiredText(node, "erasurePolicy", kind.toString()), "erasure policy"),
                textSet(node, "useDecisionIds", kind.toString()));
    }

    static Map<String, SourceUseDecision> parseUseDecisions(JsonNode root) {
        return parseUseDecisions(root, CURRENT_VERSION);
    }

    private static Map<String, SourceUseDecision> parseUseDecisions(
            JsonNode root, SourceContractVersion expectedVersion) {
        requireObject(root, "source-use decisions");
        rejectUnknown(root, Set.of("contractVersion", "decisions"), "source-use decisions");
        SourceContractVersion version =
                new SourceContractVersion(requiredText(root, "contractVersion", "source-use decisions"));
        if (!expectedVersion.equals(version)) {
            throw new IllegalStateException("Source-use decisions have unexpected contract version: " + version);
        }
        Map<String, SourceUseDecision> decisions = new HashMap<>();
        for (JsonNode node : requiredArray(root, "decisions", "source-use decisions")) {
            requireObject(node, "source-use decision");
            rejectUnknown(
                    node,
                    Set.of(
                            "id",
                            "sourceKind",
                            "purpose",
                            "basis",
                            "outcome",
                            "modelProcessor",
                            "retentionPolicy",
                            "erasurePolicy",
                            "recordedAt",
                            "reviewer",
                            "decidedAt",
                            "expiresAt"),
                    "source-use decision");
            String id = requiredText(node, "id", "source-use decision");
            SourceUseDecision decision = new SourceUseDecision(
                    id,
                    new SourceKind(requiredText(node, "sourceKind", id)),
                    enumValue(SourceUsePurpose.class, requiredText(node, "purpose", id), "source-use purpose"),
                    enumValue(SourceUseBasis.class, requiredText(node, "basis", id), "source-use basis"),
                    enumValue(SourceUseOutcome.class, requiredText(node, "outcome", id), "source-use outcome"),
                    optionalText(node, "modelProcessor", id),
                    enumValue(RetentionPolicy.class, requiredText(node, "retentionPolicy", id), "retention policy"),
                    enumValue(ErasurePolicy.class, requiredText(node, "erasurePolicy", id), "erasure policy"),
                    requiredInstant(node, "recordedAt", id),
                    optionalText(node, "reviewer", id),
                    optionalInstant(node, "decidedAt", id),
                    optionalInstant(node, "expiresAt", id));
            if (decisions.put(id, decision) != null) {
                throw new IllegalStateException("Duplicate source-use decision: " + id);
            }
        }
        return Map.copyOf(decisions);
    }

    static void validateUseDecisions(ArtifactSourceCatalog catalog, Map<String, SourceUseDecision> decisions) {
        Set<String> referencedDecisionIds = catalog.sources().stream()
                .flatMap(source -> source.useDecisionIds().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!decisions.keySet().equals(referencedDecisionIds)) {
            throw new IllegalStateException("Source-use decisions must match the catalog exactly");
        }
        for (ArtifactSourceContract source : catalog.sources()) {
            java.util.EnumSet<SourceUsePurpose> covered = java.util.EnumSet.noneOf(SourceUsePurpose.class);
            for (String decisionId : source.useDecisionIds()) {
                SourceUseDecision decision = Objects.requireNonNull(decisions.get(decisionId));
                if (!decision.sourceKind().equals(source.kind())) {
                    throw new IllegalStateException("Source-use decision kind does not match: " + decisionId);
                }
                if (!covered.add(decision.purpose())) {
                    throw new IllegalStateException("Duplicate source-use purpose decision: " + decisionId);
                }
                if (!decision.retentionPolicy().equals(source.retentionPolicy())
                        || !decision.erasurePolicy().equals(source.erasurePolicy())) {
                    throw new IllegalStateException("Source-use decision lifecycle does not match: " + decisionId);
                }
            }
            if (!covered.equals(java.util.EnumSet.allOf(SourceUsePurpose.class))) {
                throw new IllegalStateException(
                        "Source-use decisions do not cover every product purpose: " + source.kind());
            }
        }
    }

    private static JsonNode read(JsonMapper objectMapper, String resource) {
        return read(objectMapper, readBytes(resource), resource);
    }

    private static byte[] readBytes(String resource) {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read artifact-source contract resource: " + resource, exception);
        }
    }

    private static JsonNode read(JsonMapper objectMapper, byte[] bytes, String resource) {
        try {
            return objectMapper.readTree(bytes);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Cannot parse artifact-source contract resource: " + resource, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        requireObject(value, context + "." + field);
        return value;
    }

    private static void requireObject(@Nullable JsonNode node, String context) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(context + " must be an object");
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalStateException(context + "." + field + " must be a non-empty array");
        }
        return value;
    }

    private static Set<String> textSet(JsonNode node, String field, String context) {
        Set<String> values = new HashSet<>();
        for (JsonNode value : requiredArray(node, field, context)) {
            if (!value.isString() || value.asString().isBlank()) {
                throw new IllegalStateException(context + "." + field + " must contain non-blank strings");
            }
            if (!values.add(value.asString())) {
                throw new IllegalStateException(context + "." + field + " contains duplicate: " + value.asString());
            }
        }
        return values;
    }

    private static <E extends Enum<E>> Set<E> enumSet(Class<E> type, JsonNode node, String field, String context) {
        Set<E> values = new HashSet<>();
        for (String value : textSet(node, field, context)) {
            values.add(enumValue(type, value, field));
        }
        return values;
    }

    private static String requiredText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException(context + "." + field + " must be non-blank text");
        }
        return value.asString();
    }

    private static @Nullable String optionalText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException(context + "." + field + " must be null or non-blank text");
        }
        return value.asString();
    }

    private static Instant requiredInstant(JsonNode node, String field, String context) {
        String value = requiredText(node, field, context);
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(context + "." + field + " must be an ISO-8601 instant", exception);
        }
    }

    private static @Nullable Instant optionalInstant(JsonNode node, String field, String context) {
        String value = optionalText(node, field, context);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(context + "." + field + " must be an ISO-8601 instant", exception);
        }
    }

    private static boolean requiredBoolean(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException(context + "." + field + " must be boolean");
        }
        return value.asBoolean();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown " + field + ": " + value, exception);
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String context) {
        node.properties().forEach(entry -> {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalStateException("Unknown field in " + context + ": " + entry.getKey());
            }
        });
    }
}
