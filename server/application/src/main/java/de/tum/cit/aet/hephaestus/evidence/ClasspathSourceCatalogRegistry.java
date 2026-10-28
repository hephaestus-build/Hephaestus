package de.tum.cit.aet.hephaestus.evidence;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ClasspathSourceCatalogRegistry {
    public static final String CURRENT_VERSION_VALUE = "3.0.0";
    public static final SourceContractVersion CURRENT_VERSION = new SourceContractVersion(CURRENT_VERSION_VALUE);
    static final String CATALOG_RESOURCE = "contracts/source-use/" + CURRENT_VERSION_VALUE + "/catalog.json";
    static final String USE_DECISIONS_RESOURCE =
            "contracts/source-use/" + CURRENT_VERSION_VALUE + "/source-use-decisions.json";
    private final SourceCatalog catalog;
    private final String catalogDigest;
    private final Map<String, SourceUseDecision> useDecisions;
    private final Clock clock;

    @Autowired
    public ClasspathSourceCatalogRegistry(JsonMapper objectMapper, Clock clock) {
        this(
                objectMapper,
                clock,
                new ClassPathResource(CATALOG_RESOURCE),
                new ClassPathResource(USE_DECISIONS_RESOURCE));
    }

    ClasspathSourceCatalogRegistry(
            JsonMapper objectMapper, Clock clock, Resource catalogResource, Resource decisionsResource) {
        this.clock = clock;
        var mapper = StrictJson.configure(objectMapper.rebuild())
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
        byte[] catalogBytes = readBytes(catalogResource);
        this.catalog = checkVersion(mapper.readValue(catalogBytes, SourceCatalog.class));
        this.catalogDigest = sha256(catalogBytes);
        this.useDecisions = decisions(mapper.readValue(readBytes(decisionsResource), UseDecisions.class));
        validateUseDecisions(catalog, useDecisions);
    }

    public SourceCatalog current() {
        return catalog;
    }

    public String catalogDigest() {
        return catalogDigest;
    }

    public SourceContract requireSource(SourceKind kind) {
        return catalog.source(kind).orElseThrow(() -> new IllegalArgumentException("Unknown source kind: " + kind));
    }

    /** Only a reviewed, unexpired decision for this source and purpose permits a runtime read. */
    public boolean isSourceUsePermitted(SourceKind kind, SourceUsePurpose purpose) {
        return requireSource(kind).useDecisionIds().stream()
                .map(this::requireUseDecision)
                .anyMatch(decision -> decision.permitsAt(clock.instant(), purpose));
    }

    public Set<SourceKind> requireSourcesFor(String itemKind) {
        Set<SourceKind> sources = catalog.sourcesFor(itemKind);
        if (sources.isEmpty())
            throw new IllegalArgumentException("No evidence source applies to item kind: " + itemKind);
        return sources;
    }

    public SourceUseDecision requireUseDecision(String decisionId) {
        SourceUseDecision decision = useDecisions.get(decisionId);
        if (decision == null) throw new IllegalArgumentException("Unknown source-use decision: " + decisionId);
        return decision;
    }

    public Optional<Instant> earliestUseDecisionExpiry(@Nullable SourceUsePurpose purpose) {
        return useDecisions.values().stream()
                .filter(decision -> purpose == null || decision.purpose() == purpose)
                .map(SourceUseDecision::expiresAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo);
    }

    private static SourceCatalog checkVersion(SourceCatalog catalog) {
        if (!CURRENT_VERSION.equals(catalog.version()))
            throw new IllegalStateException("Catalog resource has unexpected version: " + catalog.version());
        return catalog;
    }

    private static Map<String, SourceUseDecision> decisions(UseDecisions document) {
        if (!CURRENT_VERSION.equals(document.contractVersion()))
            throw new IllegalStateException(
                    "Source-use decisions have unexpected contract version: " + document.contractVersion());
        Map<String, SourceUseDecision> decisions = new HashMap<>();
        for (SourceUseDecision decision : document.decisions()) {
            if (decisions.put(decision.id(), decision) != null)
                throw new IllegalStateException("Duplicate source-use decision: " + decision.id());
        }
        return Map.copyOf(decisions);
    }

    private record UseDecisions(SourceContractVersion contractVersion, List<SourceUseDecision> decisions) {
        @JsonCreator
        UseDecisions {
            Objects.requireNonNull(contractVersion, "contractVersion");
            decisions = List.copyOf(decisions);
            if (decisions.isEmpty()) throw new IllegalArgumentException("Source-use decisions must not be empty");
        }
    }

    static void validateUseDecisions(SourceCatalog catalog, Map<String, SourceUseDecision> decisions) {
        Set<String> referencedDecisionIds = catalog.sources().stream()
                .flatMap(source -> source.useDecisionIds().stream())
                .collect(Collectors.toUnmodifiableSet());
        if (!decisions.keySet().equals(referencedDecisionIds)) {
            throw new IllegalStateException("Source-use decisions must match the catalog exactly");
        }
        for (SourceContract source : catalog.sources()) {
            EnumSet<SourceUsePurpose> covered = EnumSet.noneOf(SourceUsePurpose.class);
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
            if (!covered.equals(EnumSet.allOf(SourceUsePurpose.class))) {
                throw new IllegalStateException(
                        "Source-use decisions do not cover every product purpose: " + source.kind());
            }
        }
    }

    private static byte[] readBytes(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read source contract resource: " + resource, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
