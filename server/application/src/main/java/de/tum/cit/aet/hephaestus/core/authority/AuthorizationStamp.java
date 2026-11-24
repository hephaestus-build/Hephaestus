package de.tum.cit.aet.hephaestus.core.authority;

/**
 * The authorization generations that a run or an action started with (ADR 0134 decision 8). A boundary compares them
 * with the current generations, and refuses when one moved on.
 *
 * @param workspace the workspace's authorization generation when the work was requested
 */
public record AuthorizationStamp(long workspace) {
    public AuthorizationStamp {
        if (workspace < 0) throw new IllegalArgumentException("A generation counts from 0");
    }
}
