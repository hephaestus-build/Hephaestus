package de.tum.cit.aet.hephaestus.agent.context;

import java.nio.file.Path;
import java.util.Objects;

/** A captured directory transferred by traversal, not expanded into per-file manifest entries. */
public record EvidenceDirectory(String target, Path source) {
    public EvidenceDirectory {
        Objects.requireNonNull(target);
        Objects.requireNonNull(source);
        if (target.isBlank()
                || target.startsWith("/")
                || !target.endsWith("/")
                || target.contains("\\")
                || target.indexOf('\0') >= 0
                || java.util.Arrays.stream(
                                target.substring(0, target.length() - 1).split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Invalid evidence directory target");
        source = source.toAbsolutePath().normalize();
    }
}
