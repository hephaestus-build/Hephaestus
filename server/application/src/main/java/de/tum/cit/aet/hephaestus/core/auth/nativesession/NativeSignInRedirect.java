package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * The redirect that ends a native sign-in in the app. It carries only the single-use handoff code or an
 * error code, and always the app's own {@code state}, so the app can reject a callback it did not start.
 * Tokens never travel in a URL.
 */
public final class NativeSignInRedirect {

    private NativeSignInRedirect() {}

    public static String success(String redirectUri, String code, String state) {
        return UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", code)
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    public static String error(String redirectUri, String error, String state) {
        return UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", error)
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }
}
