package de.tum.cit.aet.hephaestus.agent.context;

import java.util.Map;

/**
 * Projects integration-owned source data into read-only workspace inputs. Transformations stay
 * practice-agnostic and declare their fidelity in the artifact-source contract; practice-specific features
 * belong downstream.
 */
public interface ContentSource {
    String OUTPUT_PREFIX = "inputs/context/";

    boolean supports(ContextRequest request);

    default boolean required() {
        return true;
    }

    /**
     * Whether {@code path} belongs to this provider's declared read-only namespace. Bulk sources may
     * override the default for a connector-owned {@code inputs/sources/...} prefix.
     */
    default boolean ownsPath(String path) {
        return path.startsWith(OUTPUT_PREFIX);
    }

    void contribute(ContextRequest request, Map<String, byte[]> files);
}
