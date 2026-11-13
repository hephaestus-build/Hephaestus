package de.tum.cit.aet.hephaestus.integration.slack.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
public class SlackResourceAdapter implements ProviderResourceAdapter {
    private static final String ORIGIN = "https://slack.com";
    private final SlackOAuthClient oauth;

    public SlackResourceAdapter(SlackOAuthClient oauth) {
        this.oauth = oauth;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public List<Resource> resources(
            IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        if (!(config instanceof ConnectionConfig.SlackConfig slack) || slack.teamId() == null) return List.of();
        List<Resource> identities = new ArrayList<>();
        // Older installations with no recorded bot user conservatively fence the entire team until admin confirmation.
        String botUser = slack.botUserId() == null ? "*" : slack.botUserId();
        identities.add(new Resource(kind(), ORIGIN, ResourceType.BOT_USER, slack.teamId(), botUser, null));
        String tokenFingerprint = credential instanceof BearerToken bearer
                ? ProviderResourceAdapter.slackTokenFingerprint(bearer.token())
                : slack.botTokenFingerprint();
        if (tokenFingerprint != null) {
            identities.add(new Resource(
                    kind(), ORIGIN, ResourceType.BOT_TOKEN, slack.teamId() + "/" + botUser, tokenFingerprint, null));
        }
        return List.copyOf(identities);
    }

    @Override
    public Removal remove(Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch) {
        // The bot-user guard is not itself a separately revocable provider resource.
        if (resource.type() == ResourceType.BOT_USER) return Removal.ADMIN_CONFIRMATION;
        if (!(credential instanceof BearerToken bearer)
                || !resource.resourceId().equals(ProviderResourceAdapter.slackTokenFingerprint(bearer.token())))
            return Removal.NEEDS_AUTHORIZATION;
        SlackOAuthClient.BotIdentity identity;
        try {
            identity = oauth.botIdentity(bearer.token());
        } catch (SlackOAuthException rejected) {
            if ("token_revoked".equals(rejected.getMessage())) return Removal.VERIFIED_ABSENT;
            if ("invalid_auth".equals(rejected.getMessage()) || "account_inactive".equals(rejected.getMessage()))
                return Removal.NEEDS_AUTHORIZATION;
            throw rejected;
        }
        if (!(identity.teamId() + "/" + identity.userId()).equals(resource.parentId())
                && !(identity.teamId() + "/*").equals(resource.parentId())) return Removal.DIFFERENT_OWNER;
        if (!beforeDispatch.getAsBoolean()) return Removal.CANCELLED;
        oauth.revokeStrict(bearer.token());
        // The exact token is spent; its separate bot-user guard still needs explicit confirmation.
        return Removal.TERMINAL_SUCCESS;
    }
}
