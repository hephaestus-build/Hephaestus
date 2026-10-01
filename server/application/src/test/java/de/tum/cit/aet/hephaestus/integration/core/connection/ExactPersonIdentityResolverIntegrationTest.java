package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class ExactPersonIdentityResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ExactPersonIdentityResolver resolver;

    @Autowired
    private de.tum.cit.aet.hephaestus.core.privacy.PersonDataService personData;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityLinkRepository links;

    @Test
    void shouldPreviewAndExportEveryRegisteredStoreForGitLabOnlyPerson() {
        var provider = provider(IdentityProviderType.GITLAB);
        var user = user(provider, 42);
        long administratorId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Administrator")).getId());
        var snapshot = personData.preview(administratorId, null, List.of(identity(provider, "42", null)));
        var export = personData.export(snapshot.request().getId());
        java.util.Set<String> exportedStores = new java.util.TreeSet<>();
        export.path("stores").propertyNames().forEach(exportedStores::add);
        java.util.Map<String, Long> counts = new tools.jackson.databind.ObjectMapper()
                .readValue(snapshot.request().getCountsJson(), new tools.jackson.core.type.TypeReference<>() {});
        assertThat(exportedStores).containsExactlyInAnyOrderElementsOf(counts.keySet());
        counts.forEach((store, count) -> assertThat(
                        (long) export.path("stores").path(store).size())
                .as("Frozen preview/export parity for %s", store)
                .isEqualTo(count));
        assertThat(export.path("stores").path("user").size()).isEqualTo(1);
        assertThat(export.path("stores").path("user").get(0).path("id").asLong())
                .isEqualTo(user.getId());
        personData.requestErasure(snapshot.request().getId(), administratorId, true);
        personData.run(snapshot.request().getId());
        var receipt = personData.get(snapshot.request().getId()).request();
        assertThat(receipt.getState())
                .isEqualTo(de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest.State.COMPLETE);
        java.util.Map<String, Long> completed = new tools.jackson.databind.ObjectMapper()
                .readValue(receipt.getCompletedJson(), new tools.jackson.core.type.TypeReference<>() {});
        assertThat(completed.keySet()).containsExactlyInAnyOrderElementsOf(exportedStores);
        assertThat(receipt.getScopeJson()).isNull();
        assertThat(receipt.getSelectionsJson()).isNull();
        assertThat(users.findById(user.getId()).orElseThrow().getLogin()).startsWith("erased-");
        personData.requestErasure(snapshot.request().getId(), administratorId, true);
        personData.run(snapshot.request().getId());
        assertThat(personData.get(snapshot.request().getId()).request().getCompletedJson())
                .isEqualTo(receipt.getCompletedJson());
    }

    @Test
    void shouldResolveGitLabOnlyPersonWithoutAnAccount() {
        var provider = provider(IdentityProviderType.GITLAB);
        var user = user(provider, 42);
        var identity = identity(provider, "42", null);

        var scope = resolver.resolve(null, List.of(identity));

        assertThat(scope.accountId()).isNull();
        assertThat(scope.identities()).containsExactly(identity);
        assertThat(scope.userIds()).containsExactly(user.getId());
    }

    @Test
    void shouldKeepEqualNativeIdsInDifferentProviderInstancesSeparate() {
        var first = provider(IdentityProviderType.GITLAB);
        var second = provider(IdentityProviderType.GITLAB);
        var selected = user(first, 42);
        var other = user(second, 42);
        // Equal contact details are not ownership proof.
        selected.setEmail("same@example.com");
        other.setEmail("same@example.com");
        users.saveAllAndFlush(List.of(selected, other));

        var scope = resolver.resolve(null, List.of(identity(first, "42", null)));

        assertThat(scope.userIds()).containsExactly(selected.getId()).doesNotContain(other.getId());
    }

    @Test
    void shouldIncludeDisabledLinksAndResolveActorsByNativeKeyInsteadOfCachedActor() {
        var github = provider(IdentityProviderType.GITHUB);
        var gitlab = provider(IdentityProviderType.GITLAB);
        var first = user(github, 42);
        var second = user(gitlab, 84);
        var account = accounts.saveAndFlush(new Account("Subject"));
        link(account, github, "42", null, null);
        var disabled = link(account, gitlab, "84", null, null);
        disabled.setDisabledAt(Instant.now());
        links.saveAndFlush(disabled);

        var scope = resolver.resolve(null, List.of(identity(github, "42", null)));

        assertThat(scope.accountId()).isEqualTo(account.getId());
        assertThat(scope.identities())
                .containsExactlyInAnyOrder(identity(github, "42", null), identity(gitlab, "84", null));
        assertThat(scope.userIds()).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void shouldStopWhenCachedActorPointsAtAnotherPersonsProfile() {
        var github = provider(IdentityProviderType.GITHUB);
        var selected = user(github, 42);
        var other = user(github, 84);
        var account = accounts.saveAndFlush(new Account("Selected account"));
        link(account, github, "42", null, other.getId());

        assertThatThrownBy(() -> resolver.resolve(account.getId(), List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("conflicting cached actor");
        assertThat(users.findById(selected.getId())).isPresent();
        assertThat(users.findById(other.getId())).isPresent();
    }

    @Test
    void shouldStopWhenAnIdentityBelongsToAnotherAccount() {
        var github = provider(IdentityProviderType.GITHUB);
        var account = accounts.saveAndFlush(new Account("Requested subject"));
        var other = accounts.saveAndFlush(new Account("Other person"));
        link(other, github, "42", null, null);

        assertThatThrownBy(() -> resolver.resolve(account.getId(), List.of(identity(github, "42", null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("different accounts");
        assertThat(links.findActiveByAccountId(Objects.requireNonNull(other.getId())))
                .hasSize(1);
    }

    @Test
    void shouldStopWhenTwoSuppliedIdentitiesBelongToDifferentAccounts() {
        var github = provider(IdentityProviderType.GITHUB);
        var first = accounts.saveAndFlush(new Account("First"));
        var second = accounts.saveAndFlush(new Account("Second"));
        link(first, github, "42", null, null);
        link(second, github, "84", null, null);

        assertThatThrownBy(() ->
                        resolver.resolve(null, List.of(identity(github, "42", null), identity(github, "84", null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void shouldRequireSlackTeamAndKeepNativeWorkspaceIdsSeparate() {
        var slack = provider(IdentityProviderType.SLACK);
        var first = accounts.saveAndFlush(new Account("First Slack person"));
        var second = accounts.saveAndFlush(new Account("Second Slack person"));
        link(first, slack, "U42", "T1", null);
        link(second, slack, "U42", "T2", null);

        assertThat(resolver.resolve(null, List.of(identity(slack, "U42", "T1"))).accountId())
                .isEqualTo(first.getId());
        assertThatThrownBy(() -> resolver.resolve(null, List.of(identity(slack, "U42", null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("native Slack workspace id");
    }

    @Test
    void shouldResolveOutlineWithoutAnScmActor() {
        var outline = provider(IdentityProviderType.OUTLINE);
        var identity = identity(outline, UUID.randomUUID().toString(), null);
        var scope = resolver.resolve(null, List.of(identity));
        assertThat(scope.identities()).containsExactly(identity);
        assertThat(scope.userIds()).isEmpty();
        assertThat(scope.accountId()).isNull();
    }

    @Test
    void shouldRejectMissingOrNoncanonicalKeysInsteadOfMatchingLogins() {
        var gitlab = provider(IdentityProviderType.GITLAB);
        user(gitlab, 42);
        for (String subject : List.of(
                "042", "+42", "0", "-42", "alice", "42@example.com", "9223372036854775808", "99999999999999999999")) {
            assertThatThrownBy(() -> resolver.resolve(null, List.of(identity(gitlab, subject, null))))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("native numeric user id");
        }
        assertThatThrownBy(() -> resolver.resolve(null, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exact provider identity");
        assertThatThrownBy(() -> resolver.resolve(Long.MAX_VALUE, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("account id does not exist");
        assertThatThrownBy(() -> resolver.resolve(null, List.of(new PersonIdentity(Long.MAX_VALUE, "42", null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("provider instance id does not exist");
    }

    private IdentityProvider provider(IdentityProviderType type) {
        if (type == IdentityProviderType.SLACK)
            return providers
                    .findByTypeAndServerUrl(type, "https://slack.com")
                    .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(type, "https://slack.com")));
        return providers.saveAndFlush(new IdentityProvider(type, "https://" + UUID.randomUUID() + ".example.com"));
    }

    private User user(IdentityProvider provider, long nativeId) {
        return users.saveAndFlush(TestUserFactory.createUser(nativeId, "privacy-" + UUID.randomUUID(), provider));
    }

    private static PersonIdentity identity(IdentityProvider provider, String subject, @Nullable String teamId) {
        return new PersonIdentity(Objects.requireNonNull(provider.getId()), subject, teamId);
    }

    private IdentityLink link(
            Account account,
            IdentityProvider provider,
            String subject,
            @Nullable String teamId,
            @Nullable Long actorId) {
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(provider.getId()));
        link.setSubject(subject);
        link.setTeamId(teamId);
        link.setExternalActorId(actorId);
        return links.saveAndFlush(link);
    }

    @Test
    void shouldResolveOutlineOAuthWorkspaceKeysWithoutLosingTheLinkedAccount() {
        var outline = provider(IdentityProviderType.OUTLINE);
        var account = accounts.saveAndFlush(new Account("Outline person"));
        String subject = UUID.randomUUID().toString();
        link(account, outline, subject, "outline-workspace", null);
        var scope = resolver.resolve(null, List.of(identity(outline, subject, null)));
        assertThat(scope.accountId()).isEqualTo(account.getId());
        assertThat(scope.identities())
                .containsExactlyInAnyOrder(
                        identity(outline, subject, null), identity(outline, subject, "outline-workspace"));
        assertThat(resolver.resolve(account.getId(), List.of()).identities())
                .containsExactlyInAnyOrderElementsOf(scope.identities());
    }

    @Test
    void shouldRejectConflictingOutlineWorkspaceLinksBeforeSelectingMirroredContent() {
        var outline = provider(IdentityProviderType.OUTLINE);
        var first = accounts.saveAndFlush(new Account("First Outline person"));
        var second = accounts.saveAndFlush(new Account("Second Outline person"));
        String subject = UUID.randomUUID().toString();
        link(first, outline, subject, "outline-workspace-a", null);
        link(second, outline, subject, "outline-workspace-b", null);
        assertThatThrownBy(() -> resolver.resolve(null, List.of(identity(outline, subject, "outline-workspace-a"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }
}
