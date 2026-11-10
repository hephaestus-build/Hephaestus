package de.tum.cit.aet.hephaestus.evidence.capture;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A captured directory transferred by traversal, not expanded into per-file manifest entries.
 *
 * @param checkout how another server builds this checkout again; null for a directory that is not a checkout
 */
public record EvidenceDirectory(
        String target, Path source, @Nullable CheckoutKey checkout) {
    public EvidenceDirectory(String target, Path source) {
        this(target, source, null);
    }

    /** The same directory at another place, such as an attempt's copy of it. */
    public EvidenceDirectory at(Path copy) {
        return new EvidenceDirectory(target, copy, checkout);
    }

    public EvidenceDirectory {
        Objects.requireNonNull(target);
        Objects.requireNonNull(source);
        if (target.isBlank()
                || target.startsWith("/")
                || !target.endsWith("/")
                || target.contains("\\")
                || target.indexOf('\0') >= 0
                || Arrays.stream(target.substring(0, target.length() - 1).split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Invalid evidence directory target");
        source = source.toAbsolutePath().normalize();
    }
}
