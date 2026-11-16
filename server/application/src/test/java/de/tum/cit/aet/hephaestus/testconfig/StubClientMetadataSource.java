package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.auth.mcp.ClientMetadataSource;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/** Client metadata documents served from memory, because tests resolve no real hosts. */
@TestComponent
@Primary
public class StubClientMetadataSource implements ClientMetadataSource {

    private static final Map<URI, String> DOCUMENTS = new ConcurrentHashMap<>();

    public static void serve(String clientId, String document) {
        DOCUMENTS.put(URI.create(clientId), document);
    }

    @Override
    public Optional<String> fetch(URI clientId) {
        return Optional.ofNullable(DOCUMENTS.get(clientId));
    }
}
