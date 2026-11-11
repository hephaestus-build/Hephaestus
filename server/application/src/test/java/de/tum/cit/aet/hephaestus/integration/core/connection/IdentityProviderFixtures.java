package de.tum.cit.aet.hephaestus.integration.core.connection;

/** Identity providers that integration tests build on. */
public final class IdentityProviderFixtures {

    private static final String GITHUB_COM = "https://github.com";

    private IdentityProviderFixtures() {}

    /**
     * The github.com provider. Nothing seeds it, and {@code DatabaseTestUtils.cleanDatabase()}
     * truncates the table, so a test that needs it creates it when it is absent instead of relying
     * on an earlier test in the same JVM.
     */
    public static IdentityProvider gitHub(IdentityProviderRepository providers) {
        return providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_COM)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, GITHUB_COM)));
    }
}
