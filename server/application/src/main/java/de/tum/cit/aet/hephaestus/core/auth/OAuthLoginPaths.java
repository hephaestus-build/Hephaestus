package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.web.ApiPath;

/**
 * The public paths of the OAuth sign-in endpoints that Spring Security's {@code oauth2Login} serves. A login
 * provider's callback is registered at the provider, so {@link #CALLBACK} is part of the operator's setup.
 */
public final class OAuthLoginPaths {

    /** The authorization request to the provider, followed by the registration ID. */
    public static final String AUTHORIZATION = ApiPath.PREFIX + "/oauth2/authorization";

    /** The provider's redirect back, followed by the registration ID. */
    public static final String CALLBACK = ApiPath.PREFIX + "/login/oauth2/code";

    private OAuthLoginPaths() {}
}
