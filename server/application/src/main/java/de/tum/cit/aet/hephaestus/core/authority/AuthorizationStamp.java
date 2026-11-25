package de.tum.cit.aet.hephaestus.core.authority;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The authorization generations that a run or an action started with (ADR 0134 decision 8). A boundary compares them
 * with the current generations, and refuses when one moved on.
 *
 * @param workspace the workspace's authorization generation when the work was requested
 * @param accounts the generation of each person whose authority the work depends on, such as a review's developers
 *     or a turn's owner, by account; empty when the work depends on no person's choice
 */
public record AuthorizationStamp(long workspace, Map<Long, Long> accounts) {
    public AuthorizationStamp(long workspace, @Nullable Map<Long, Long> accounts) {
        if (workspace < 0) throw new IllegalArgumentException("A generation counts from 0");
        this.workspace = workspace;
        this.accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
        if (this.accounts.values().stream().anyMatch(generation -> generation < 0))
            throw new IllegalArgumentException("A generation counts from 0");
    }

    /** The stamp of work that depends on no person's own choice. */
    public AuthorizationStamp(long workspace) {
        this(workspace, Map.of());
    }
}
