package de.tum.cit.aet.hephaestus.practices.advice;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Writes of lasting advice (ADR 0160). They stay off until a release after the advice tables join the person-data
 * inventory.
 *
 * @param writableWorkspaceIds the workspaces where the mentor may write advice. Empty by default, so no workspace
 *     writes advice.
 * @param allowWithoutErasureListener lets a listed workspace write advice while nothing tells conversations that
 *     advice lost its evidence. An answer that quoted advice then keeps the quote after the evidence is erased. Use it
 *     only in a synthetic proof environment, never in production. The server logs a warning at startup when it is on.
 */
@ConfigurationProperties(prefix = "hephaestus.practices.advice")
public record PracticeAdviceProperties(
        @DefaultValue Set<Long> writableWorkspaceIds,
        @DefaultValue("false") boolean allowWithoutErasureListener) {
    public PracticeAdviceProperties {
        writableWorkspaceIds = Set.copyOf(writableWorkspaceIds);
    }
}
