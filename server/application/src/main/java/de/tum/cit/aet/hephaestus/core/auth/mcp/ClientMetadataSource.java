package de.tum.cit.aet.hephaestus.core.auth.mcp;

import java.net.URI;
import java.util.Optional;

/** Fetches a client ID metadata document; empty when it cannot be read within the limits. */
public interface ClientMetadataSource {

    Optional<String> fetch(URI clientId);
}
