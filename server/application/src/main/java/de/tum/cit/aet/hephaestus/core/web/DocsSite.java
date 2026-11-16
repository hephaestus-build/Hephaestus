package de.tum.cit.aet.hephaestus.core.web;

import java.util.regex.Pattern;

/**
 * The public docs site, and the one place on the server that names it. Its root serves the latest release, and
 * {@code next/} serves {@code main}. A release build links to the root; any other build runs unreleased code, so it
 * links to {@code next/}. The web app applies the same rule to the same version ({@code webapp/src/lib/docs-url.ts}).
 */
public final class DocsSite {

    public static final String ROOT = "https://docs.hephaestus.build/";

    /** A version that the release train publishes: {@code 1.2.3} or {@code 1.2.3-rc.4}. */
    private static final Pattern RELEASE =
            Pattern.compile("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-rc\\.(?:0|[1-9]\\d*))?");

    private DocsSite() {}

    /** The docs root for a build of this version ({@code spring.application.version}). */
    public static String base(String version) {
        return RELEASE.matcher(version).matches() ? ROOT : ROOT + "next/";
    }

    /** A page of the docs for a build of this version, such as {@code admin/runtime-roles#database-migrations}. */
    public static String page(String version, String path) {
        return base(version) + path;
    }
}
