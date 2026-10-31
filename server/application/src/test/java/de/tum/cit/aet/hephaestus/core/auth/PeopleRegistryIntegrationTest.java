package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipRoster;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PeopleRegistryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PeopleRegistry people;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private MembershipRoster roster;

    @Autowired
    private PracticeModelTestFixtures fixtures;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldKeepOneAccountWhenConcurrentRosterWritesAndClaimsUseTheSameIdentity() throws Exception {
        var identity = identity(null);
        var start = new CountDownLatch(1);
        var rosterResults = new ArrayList<Future<Long>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++)
                rosterResults.add(executor.submit(() -> {
                    start.await();
                    return people.ensureHuman(identity, "Roster developer");
                }));
            start.countDown();
            long accountId = rosterResults.getFirst().get(30, TimeUnit.SECONDS);
            for (var result : rosterResults)
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(accountId);
            var claim = executor.submit(() -> people.claim(identity));
            var roster = executor.submit(() -> people.ensureHuman(identity, "Renamed developer"));
            assertThat(claim.get(30, TimeUnit.SECONDS)).isEqualTo(accountId);
            assertThat(roster.get(30, TimeUnit.SECONDS)).isEqualTo(accountId);
            assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVE);
            assertThat(identities.findByExactKey(identity.providerId(), identity.subject(), ""))
                    .hasSize(1);
        }
    }

    @Test
    void shouldKeepDisabledAuthorityWhenRosterSyncSeesAnIdentityAgain() {
        var identity = identity("team-1");
        long accountId = people.ensureHuman(identity, "Developer");
        var row = identities.findByAccount_Id(accountId).getFirst();
        row.setDisabledAt(Instant.now());
        identities.saveAndFlush(row);
        assertThatThrownBy(() -> people.ensureHuman(identity, "Developer")).isInstanceOf(ResponseStatusException.class);
        assertThat(identities.findByExactKey(identity.providerId(), identity.subject(), "team-1"))
                .hasSize(1);
        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.UNCLAIMED);
        assertThat(people.claim(identity)).isEqualTo(accountId);
        assertThat(identities
                        .findById(Objects.requireNonNull(row.getId()))
                        .orElseThrow()
                        .getDisabledAt())
                .isNull();
    }

    @Test
    void shouldMergeOverlappingMembershipsAndRetainLatestChoiceWhenAnUnclaimedAccountIsLinked() {
        var targetIdentity = identity(null);
        var sourceIdentity = gitLabIdentity();
        long targetId = people.ensureHuman(targetIdentity, "Claimed developer");
        people.claim(targetIdentity);
        long sourceId = people.ensureHuman(sourceIdentity, "Roster developer");
        var target = accounts.findById(targetId).orElseThrow();
        target.setAiChoice(AiChoice.CLOUD);
        target.setAiChoiceAt(Instant.parse("2026-01-01T00:00:00Z"));
        // The claimed developer answered Show; the roster account never answered, so it is hidden.
        target.setPublicActivityVisible(true);
        accounts.saveAndFlush(target);
        var source = accounts.findById(sourceId).orElseThrow();
        assertThat(source.isPublicActivityVisible()).isFalse();
        source.setAiChoice(AiChoice.NO_AI);
        source.setAiChoiceAt(Instant.parse("2026-02-01T00:00:00Z"));
        source.setFeedbackDeliveryEnabled(false);
        accounts.saveAndFlush(source);
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("people-" + UUID.randomUUID());
        workspace.setAccountType(AccountType.ORG);
        workspace.setDisplayName("People test");
        workspace.setAccountLogin("people");
        workspace = workspaces.saveAndFlush(workspace);
        var claimedMember =
                member(workspace, targetId, Membership.WorkspaceRole.MEMBER, Instant.parse("2026-02-01T00:00:00Z"));
        claimedMember.setLeftAt(Instant.parse("2026-03-01T00:00:00Z"));
        claimedMember.setOnboardingSeenRevision(9L);
        memberships.saveAndFlush(claimedMember);
        var rosterMember =
                member(workspace, sourceId, Membership.WorkspaceRole.ADMIN, Instant.parse("2026-01-01T00:00:00Z"));
        rosterMember.setHidden(true);
        rosterMember.setReviewSelected(true);
        rosterMember.setPublicActivitySeen(true);
        memberships.saveAndFlush(rosterMember);
        people.absorbUnclaimed(targetId, sourceId);
        assertThat(people.claim(sourceIdentity)).isEqualTo(targetId);
        var merged = memberships
                .findById(new Membership.Id(workspace.getId(), targetId))
                .orElseThrow();
        assertThat(merged.getRole()).isEqualTo(Membership.WorkspaceRole.ADMIN);
        assertThat(merged.isHidden()).isTrue();
        assertThat(merged.isReviewSelected()).isTrue();
        assertThat(merged.isPublicActivitySeen()).isTrue();
        assertThat(merged.getJoinedAt()).isEqualTo(rosterMember.getJoinedAt());
        assertThat(merged.getOnboardingSeenRevision()).isEqualTo(9L);
        assertThat(merged.getLeftAt()).isNull();
        assertThat(memberships.findById(new Membership.Id(workspace.getId(), sourceId)))
                .isEmpty();
        assertThat(accounts.findById(sourceId)).isEmpty();
        var mergedAccount = accounts.findById(targetId).orElseThrow();
        assertThat(mergedAccount.getAiChoice()).isEqualTo(AiChoice.NO_AI);
        assertThat(mergedAccount.isFeedbackDeliveryEnabled()).isFalse();
        assertThat(mergedAccount.isPublicActivityVisible()).isTrue();
        assertThat(identities.findByAccount_Id(targetId)).hasSize(2);
        assertThat(people.ensureHuman(sourceIdentity, "Developer")).isEqualTo(targetId);
    }

    @Test
    void shouldPreserveDeliveredReceiptsAndRerankClaimsWhenUnclaimedAttributionIsAbsorbed() {
        var targetIdentity = identity(null);
        long targetId = people.ensureHuman(targetIdentity, "Claimed developer");
        people.claim(targetIdentity);
        long sourceId = people.ensureHuman(gitLabIdentity(), "Roster developer");
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("claim-merge-" + UUID.randomUUID());
        workspace.setAccountType(AccountType.ORG);
        workspace.setDisplayName("Claim merge");
        workspace.setAccountLogin("claim-merge-" + UUID.randomUUID());
        workspace = workspaces.saveAndFlush(workspace);
        var work =
                fixtures.work(workspace, "merge-" + UUID.randomUUID().toString().substring(0, 8), "chat.thread", 1L);
        var older = fixtures.result(work, targetId, "Older claim");
        var newer = fixtures.result(fixtures.forkReview(work), sourceId, "Newer claim");
        var feedback =
                fixtures.feedback(newer, FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, "Confirmed copy words");
        UUID copyId = FeedbackIds.copy(feedback.getId(), FeedbackCopyKind.SUMMARY, 0);
        String marker = FeedbackIds.writeMarker(copyId);
        jdbc.update("""
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_started_at,
                write_key,write_marker,external_ref,created_at,posted_at)
            VALUES (?,?,?,'SUMMARY',0,'POSTED',CURRENT_TIMESTAMP,?,?,'retained-copy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, copyId, workspace.getId(), feedback.getId(), copyId.toString(), marker);

        people.absorbUnclaimed(targetId, sourceId);

        assertThat(jdbc.queryForObject("SELECT developer_id FROM result WHERE id=?", Long.class, newer.getId()))
                .isEqualTo(targetId);
        assertThat(jdbc.queryForObject("SELECT ended_at IS NULL FROM result WHERE id=?", Boolean.class, newer.getId()))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT end_reason FROM result WHERE id=?", String.class, older.getId()))
                .isEqualTo("OUTRANKED");
        assertThat(jdbc.queryForObject("SELECT developer_id FROM feedback WHERE id=?", Long.class, feedback.getId()))
                .isEqualTo(targetId);
        assertThat(jdbc.queryForObject("SELECT body FROM feedback WHERE id=?", String.class, feedback.getId()))
                .isEqualTo("Confirmed copy words");
        assertThat(jdbc.queryForObject("SELECT state FROM feedback WHERE id=?", String.class, feedback.getId()))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject(
                        "SELECT developer_id FROM feedback_basis WHERE feedback_id=?", Long.class, feedback.getId()))
                .isEqualTo(targetId);
        assertThat(jdbc.queryForObject("SELECT external_ref FROM feedback_copy WHERE id=?", String.class, copyId))
                .isEqualTo("retained-copy");
        assertThat(jdbc.queryForObject("SELECT write_marker FROM feedback_copy WHERE id=?", String.class, copyId))
                .isEqualTo(marker);
        assertThat(jdbc.queryForObject("SELECT state FROM feedback_copy WHERE id=?", String.class, copyId))
                .isEqualTo("POSTED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_copy WHERE feedback_id=?", Long.class, feedback.getId()))
                .isEqualTo(1L);
        assertThat(memberships.findById(new Membership.Id(workspace.getId(), sourceId)))
                .isEmpty();
        assertThat(accounts.findById(sourceId)).isEmpty();
    }

    @Test
    void shouldKeepOneOwnerWhenTwoVerifiedLinksRaceToAbsorbTheSameUnclaimedAccount() throws Exception {
        var firstIdentity = identity(null);
        var secondIdentity = identity(null);
        var sourceIdentity = gitLabIdentity();
        long firstId = people.ensureHuman(firstIdentity, "First claimed developer");
        long secondId = people.ensureHuman(secondIdentity, "Second claimed developer");
        people.claim(firstIdentity);
        people.claim(secondIdentity);
        long sourceId = people.ensureHuman(sourceIdentity, "Roster developer");
        var start = new CountDownLatch(1);
        var attempts = new ArrayList<Future<Long>>();
        var successful = new ArrayList<Long>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (long targetId : new long[] {firstId, secondId}) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    people.absorbUnclaimed(targetId, sourceId);
                    return targetId;
                }));
            }
            start.countDown();
            int conflicts = 0;
            for (var attempt : attempts) {
                try {
                    successful.add(attempt.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(ResponseStatusException.class);
                    conflicts++;
                }
            }
            assertThat(successful).hasSize(1);
            assertThat(conflicts).isEqualTo(1);
        }
        assertThat(accounts.findById(sourceId)).isEmpty();
        var owners = identities.findByExactKey(sourceIdentity.providerId(), sourceIdentity.subject(), "");
        assertThat(owners).hasSize(1);
        assertThat(owners.getFirst().getAccount().getId()).isEqualTo(successful.getFirst());
        assertThat(accounts.findById(firstId)).isPresent();
        assertThat(accounts.findById(secondId)).isPresent();
    }

    @Test
    void shouldRejectAbsorptionWhenBothAccountsAreClaimed() {
        var first = identity(null);
        var second = identity(null);
        long firstId = people.ensureHuman(first, "First");
        long secondId = people.ensureHuman(second, "Second");
        people.claim(first);
        people.claim(second);
        assertThatThrownBy(() -> people.absorbUnclaimed(firstId, secondId)).isInstanceOf(ResponseStatusException.class);
        assertThat(identities.findByAccount_Id(firstId)).hasSize(1);
        assertThat(identities.findByAccount_Id(secondId)).hasSize(1);
    }

    @Test
    void shouldPreserveLeftMembershipControlsWhenAConnectedSourceSeesTheHumanAgain() {
        long accountId = people.ensureHuman(identity(null), "Former member");
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("left-" + UUID.randomUUID());
        workspace.setAccountType(AccountType.ORG);
        workspace.setDisplayName("Left membership");
        workspace.setAccountLogin("left-" + UUID.randomUUID());
        workspace = workspaces.saveAndFlush(workspace);
        var former =
                member(workspace, accountId, Membership.WorkspaceRole.ADMIN, Instant.parse("2026-01-01T00:00:00Z"));
        former.setHidden(true);
        former.setReviewSelected(true);
        former.setLeftAt(Instant.parse("2026-02-01T00:00:00Z"));
        memberships.saveAndFlush(former);
        assertThat(roster.ensureMember(workspace.getId(), accountId)).isFalse();
        var retained = memberships
                .findById(new Membership.Id(workspace.getId(), accountId))
                .orElseThrow();
        assertThat(retained.getLeftAt()).isEqualTo(former.getLeftAt());
        assertThat(retained.getRole()).isEqualTo(Membership.WorkspaceRole.ADMIN);
        assertThat(retained.isHidden()).isTrue();
        assertThat(retained.isReviewSelected()).isTrue();
    }

    private PersonIdentity gitLabIdentity() {
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));
        return new PersonIdentity(
                Objects.requireNonNull(provider.getId()), UUID.randomUUID().toString(), null);
    }

    private PersonIdentity identity(@Nullable String team) {
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        return new PersonIdentity(
                Objects.requireNonNull(provider.getId()), UUID.randomUUID().toString(), team);
    }

    private static Membership member(
            Workspace workspace, long accountId, Membership.WorkspaceRole role, Instant joined) {
        var member = new Membership();
        member.setWorkspace(workspace);
        member.setId(new Membership.Id(workspace.getId(), accountId));
        member.setRole(role);
        member.setJoinedAt(joined);
        return member;
    }
}
