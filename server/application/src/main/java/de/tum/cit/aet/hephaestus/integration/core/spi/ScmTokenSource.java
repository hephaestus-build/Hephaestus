package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Optional;

/**
 * Per-kind credential source for SCM API calls made from outside the vendor adapter
 * (e.g. agent context preparation that needs to {@code git fetch} from the same
 * remote the runtime is bound to).
 *
 * <p>One impl per {@link IntegrationKind#family()} {@code == SCM} kind. Returning
 * {@link Optional#empty()} means "no token available for this scope on this kind"
 * — either the workspace has no active connection of this kind, or its credential
 * blob is unset.
 *
 * <p>This is intentionally narrower than {@code ApiCredentialProvider}: it only
 * exposes the bearer-token-shaped path that external (agent/) code can use, never
 * App JWTs or installation-minted tokens that require kind-specific minting.
 */
public interface ScmTokenSource {
    /** The SCM kind this token source represents. */
    IntegrationKind kind();

    /**
     * Returns a bearer access token for the given workspace scope, if available.
     *
     * <p>The token semantics depend on the kind:
     * <ul>
     *   <li>GitHub App: a minted installation token (short-lived, cached)</li>
     *   <li>GitHub PAT / GitLab PAT: the stored personal access token</li>
     * </ul>
     */
    Optional<String> accessToken(long scopeId);

    /**
     * Returns the resolved server URL the token authenticates against (e.g.
     * {@code https://gitlab.com} or {@code https://github.com}). Empty when no
     * active connection of this kind exists for the scope.
     */
    Optional<String> serverUrl(long scopeId);

    /**
     * Provider-owned remote ref that exposes the immutable review head through the target
     * repository, including for contributions submitted from forks.
     */
    Optional<String> reviewHeadRef(long pullRequestNumber);

    /** Whether the recorded review base identifies the diff base rather than the target branch tip. */
    boolean recordsReviewDiffBase();
}
