package de.tum.cit.aet.hephaestus.mentor.spi;

import org.jspecify.annotations.Nullable;

/** The transcript key: who reads one history. Slack: the connection (present), the channel and the verified thread root,
 * or null for the top level. App: no connection, and the owner's thread id. It never names where an effect is written;
 * {@link Route} does. */
public record Audience(
        Transport transport,
        @Nullable Long connectionId,
        String containerId,
        @Nullable String threadRoot) {}
