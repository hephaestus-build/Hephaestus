package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.BindParam;

/**
 * The query parameters an installed client starts a sign-in with, as it sends them (OAuth 2.0 names).
 * Every one may be missing or malformed; {@link ClientSignInStart#decide} judges them.
 */
public record ClientSignInParameters(
        @BindParam("client_id") @Nullable String clientId,
        @BindParam("redirect_uri") @Nullable String redirectUri,
        @BindParam("code_challenge") @Nullable String codeChallenge,
        @BindParam("code_challenge_method") @Nullable String codeChallengeMethod,
        @Nullable String state) {}
