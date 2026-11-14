package de.tum.cit.aet.hephaestus.integration.scm.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderPersonLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class ScmPersonMirrorLookup implements ProviderPersonLookup {
    private final UserRepository users;

    @Override
    public Set<String> providerTypes() {
        return Set.of("GITHUB", "GITLAB");
    }

    @Override
    public List<Long> mirrorIds(PersonIdentity identity) {
        long nativeId;
        try {
            nativeId = Long.parseLong(identity.subject());
            if (nativeId <= 0 || !Long.toString(nativeId).equals(identity.subject())) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "An SCM identity requires a canonical native numeric user id", e);
        }
        return users.findByNativeIdAndProviderId(nativeId, identity.providerId()).map(user -> user.getId()).stream()
                .toList();
    }
}
