package de.tum.cit.aet.hephaestus.core;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.exception.PreconditionFailedException;
import de.tum.cit.aet.hephaestus.core.exception.PreconditionRequiredException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ETag;

/** Parses and evaluates an HTTP {@code If-Match} precondition using strong entity-tag comparison. */
public record EntityTagPrecondition(List<ETag> candidates) {
    public static EntityTagPrecondition parse(String value) {
        List<ETag> candidates = ETag.parse(value);
        if (candidates.isEmpty()) {
            throw new InvalidInputException("The If-Match header must contain at least one valid entity tag.");
        }
        return new EntityTagPrecondition(List.copyOf(candidates));
    }

    public boolean matches(String tag) {
        ETag current = new ETag(tag, false);
        return candidates.stream().anyMatch(candidate -> candidate.isWildcard() || candidate.compare(current, true));
    }

    /** A release decision must name the exact version shown to the administrator; '*' is not enough. */
    public boolean matchesExact(String tag) {
        ETag current = new ETag(tag, false);
        return candidates.stream().anyMatch(candidate -> !candidate.isWildcard() && candidate.compare(current, true));
    }

    public static void requireExact(@Nullable EntityTagPrecondition precondition, String tag) {
        if (precondition == null) throw new PreconditionRequiredException();
        if (!precondition.matchesExact(tag)) throw new PreconditionFailedException();
    }

    public static String format(String tag) {
        return new ETag(tag, false).formattedTag();
    }
}
