package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewLookupData;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PageSupportWorkDisplayIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    ProductReferenceQuery references;

    @Autowired
    IssueRepository issues;

    @Autowired
    ItemIndexService index;

    @Autowired
    SqlReadMeasurement reads;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldNameTheCreatedSupportingWorkWithoutReadingAnotherWorkspace(IdentityProviderType provider) {
        var workspace = data.scenario().workspace();
        var current = lookup.work(workspace, provider, true);
        var earlier = earlier(current, provider);
        var foreign = lookup.work(data.scenario().workspace(), provider, true);

        var single = reads.measure(() -> references.items(workspace.getId(), List.of(current.itemId())));
        var batch = reads.measure(() ->
                references.items(workspace.getId(), List.of(current.itemId(), earlier.itemId(), foreign.itemId())));
        assertThat(batch.value()).containsOnlyKeys(current.itemId(), earlier.itemId());
        assertThat(batch.cost().statements()).isEqualTo(single.cost().statements());
        var named = Objects.requireNonNull(batch.value().get(earlier.itemId()));
        assertThat(named.id()).isEqualTo(Long.toString(earlier.itemId()));
        assertThat(named.title()).isEqualTo("Earlier work with the same concern");
        assertThat(named.label()).endsWith(provider == IdentityProviderType.GITHUB ? "#900" : "!900");
        assertThat(named.url()).endsWith("/900");
    }

    private ItemRef earlier(ItemRef current, IdentityProviderType provider) {
        return Objects.requireNonNull(
                new TransactionTemplate(transactions).execute(status -> createEarlier(current, provider)));
    }

    private ItemRef createEarlier(ItemRef current, IdentityProviderType provider) {
        var original =
                issues.findById(Objects.requireNonNull(current.sourceId())).orElseThrow();
        var repository = Objects.requireNonNull(original.getRepository());
        var earlier = new PullRequest();
        earlier.setProvider(original.getProvider());
        earlier.setRepository(repository);
        earlier.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        earlier.setNumber(900);
        earlier.setTitle("Earlier work with the same concern");
        earlier.setBody("Earlier work.");
        earlier.setState(Issue.State.OPEN);
        earlier.setCreatedAt(Instant.now());
        earlier.setUpdatedAt(Instant.now());
        String repositoryUrl = Objects.requireNonNull(repository.getHtmlUrl());
        earlier.setHtmlUrl(
                repositoryUrl + (provider == IdentityProviderType.GITHUB ? "/pull/900" : "/-/merge_requests/900"));
        var stored = issues.saveAndFlush(earlier);
        return index.upsert(new ItemRegistration(
                current.workspaceId(), ScmItemEvents.PULL_REQUEST, stored.getId(), current.parentId(), null));
    }
}
