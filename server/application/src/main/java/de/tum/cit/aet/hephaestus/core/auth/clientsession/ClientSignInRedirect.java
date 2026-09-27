package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The redirect that ends an installed-client sign-in at the client's registered callback. It carries
 * only the single-use handoff code or an error code, and the client's own {@code state} so the client
 * can reject a callback it did not start. Tokens never travel in a URL.
 */
public final class ClientSignInRedirect {

    private ClientSignInRedirect() {}

    public static String success(String redirectUri, String code, String state) {
        return UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", code)
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    /** {@code state} is omitted when the client sent none that could be echoed safely. */
    public static String error(String redirectUri, String error, @Nullable String state) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(redirectUri).queryParam("error", error);
        if (state != null) {
            builder.queryParam("state", state);
        }
        return builder.encode().build().toUriString();
    }
}
