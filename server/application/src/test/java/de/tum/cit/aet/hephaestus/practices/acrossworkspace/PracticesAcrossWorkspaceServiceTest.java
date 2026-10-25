package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Verdict;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceGroupSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspacePracticeSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceRangeDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitPartDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.result.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultView;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.result.StandingScale;
import de.tum.cit.aet.hephaestus.practices.result.trend.PracticeTrendService;
import de.tum.cit.aet.hephaestus.practices.result.trend.TrendProperties;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The page over the real standing reader and group summary. Only persistence, membership, source authorization and
 * open-feedback counting are stubbed: the fences themselves are covered by ResultSourceAuthorizationTest,
 * ResultVisibilityPolicyTest and MigratedHistoryReadIntegrationTest.
 */
@Tag("unit")
class PracticesAcrossWorkspaceServiceTest {
    private static final long READER = 7L;
    private static final long PROLIFIC = 8L;
    private static final long QUIET = 9L;
    private static final long HIDDEN = 10L;

    private final Instant now = Instant.parse("2026-10-20T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final List<ResultView> data = new ArrayList<>();

    private final ResultViewQuery views = mock(ResultViewQuery.class);
    private final ResultRepository results = mock(ResultRepository.class);
    private final CurrentDeveloperLookup currentDeveloper = mock(CurrentDeveloperLookup.class);
    private final ResultVisibilityPolicy visibility = mock(ResultVisibilityPolicy.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final PracticeGroupService groups = mock(PracticeGroupService.class);
    private final PracticeReviewCoverageService scope = mock(PracticeReviewCoverageService.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final PersonProcessingSuppression suppression = mock(PersonProcessingSuppression.class);
    private final PracticePageFeedbackService feedback = mock(PracticePageFeedbackService.class);
    private final EvidenceAuthorization evidence = mock(EvidenceAuthorization.class);
    private final PracticeTrendService trends = new PracticeTrendService(new TrendProperties(), clock);
    private final PracticeStandingService standings = new PracticeStandingService(
            views, new JsonMapper(), results, currentDeveloper, visibility, practices, trends, clock);
    private final PracticeGroupStandingService groupStandings =
            new PracticeGroupStandingService(standings, trends, clock);
    private final PracticesAcrossWorkspaceService service = new PracticesAcrossWorkspaceService(
            standings,
            groupStandings,
            groups,
            scope,
            results,
            memberships,
            suppression,
            currentDeveloper,
            feedback,
            evidence,
            trends,
            clock);
    private final WorkspaceContext context =
            new WorkspaceContext(1L, "test-ws", "Test", AccountType.ORG, null, Set.of(WorkspaceRole.MEMBER));

    private final PracticeGroup group = group("review-ready-work", 0);
    private final PracticeGroup peopleOnly = group("people-only", 1);
    private final Practice reviewed = practice(1, "describe-the-change", group, 0, ReviewedByKind.HEPHAESTUS);
    private final Practice off = practice(2, "small-changes", group, 1, ReviewedByKind.HEPHAESTUS);
    private final Practice nobody = practice(3, "pair-on-it", group, 2, ReviewedByKind.NOBODY);
    private final Practice unreviewed = practice(4, "talk-it-through", peopleOnly, 0, ReviewedByKind.NOBODY);

    @BeforeEach
    void setUp() {
        when(visibility.asOneRead(anyLong(), any()))
                .thenAnswer(i -> i.<Supplier<?>>getArgument(1).get());
        off.setFeedbackMode(FeedbackMode.OFF);
        when(practices.findByWorkspaceId(1L)).thenReturn(List.of(reviewed, off, nobody, unreviewed));
        when(groups.listGroups(context, true)).thenReturn(List.of(group, peopleOnly));
        when(currentDeveloper.currentDeveloperId()).thenReturn(Optional.of(READER));
        when(scope.eligibleAccountIds(1L)).thenReturn(new LinkedHashSet<>(List.of(READER, QUIET)));
        when(results.developersSince(eq(1L), any(Instant.class))).thenAnswer(i -> {
            Instant since = i.getArgument(1);
            return data.stream()
                    .filter(v -> !v.result().getRecordedAt().isBefore(since))
                    .map(v -> v.result().getDeveloperId())
                    .distinct()
                    .toList();
        });
        when(results.forDevelopersSince(eq(1L), anyCollection(), any(Instant.class), anyBoolean()))
                .thenAnswer(i -> {
                    Collection<Long> developers = i.getArgument(1);
                    Instant since = i.getArgument(2);
                    return data.stream()
                            .map(ResultView::result)
                            .filter(r -> developers.contains(r.getDeveloperId())
                                    && !r.getRecordedAt().isBefore(since))
                            .toList();
                });
        when(views.views(eq(1L), anyCollection())).thenAnswer(i -> {
            Collection<Result> rows = i.getArgument(1);
            return data.stream().filter(v -> rows.contains(v.result())).toList();
        });
        when(visibility.permitsStandingAt(eq(1L), anyCollection(), any())).thenAnswer(i -> ids(i.getArgument(1)));
        when(visibility.permitsAt(eq(1L), anyCollection(), any(), any())).thenAnswer(i -> ids(i.getArgument(1)));
        when(visibility.permitsHistory(eq(1L), anyCollection(), any())).thenAnswer(i -> ids(i.getArgument(1)));
        when(memberships.isActiveVisibleMember(1L, PROLIFIC)).thenReturn(true);
        when(memberships.isActiveVisibleMember(1L, HIDDEN)).thenReturn(false);
        when(suppression.suppressedAccountIds(anyCollection())).thenReturn(Set.of());
        when(feedback.countOpen(eq(1L), anyCollection())).thenReturn(Map.of(READER, 2, QUIET, 1));
        when(evidence.asOneRead(anyLong(), any()))
                .thenAnswer(i -> i.<Supplier<?>>getArgument(1).get());
    }

    @Test
    void shouldCountEachDeveloperOnceAtTheirProfileStandingWhenOneHasMuchMoreWork() {
        result(reviewed, READER, 1, 5, ResultOutcome.NOT_MET);
        result(off, READER, 1, 5, ResultOutcome.MET);
        for (int item = 10; item < 16; item++) result(reviewed, PROLIFIC, item, 20 + item, ResultOutcome.MET);
        result(reviewed, HIDDEN, 30, 5, ResultOutcome.NOT_MET);

        var overview = service.read(context);

        assertThat(overview.groups())
                .extracting(WorkspaceGroupSplitDTO::groupSlug)
                .containsExactly(group.getSlug());
        var row = overview.groups().getFirst();
        assertThat(row.practices())
                .extracting(WorkspacePracticeSplitDTO::practiceSlug)
                .containsExactly(reviewed.getSlug(), off.getSlug());
        var first = row.practices().getFirst();
        assertThat(first.split().parts())
                .extracting(WorkspaceSplitPartDTO::standing, WorkspaceSplitPartDTO::developers)
                .containsExactly(tuple(Verdict.DEVELOPING, 0), tuple(Verdict.MIXED, 1), tuple(Verdict.STRENGTH, 1));
        assertThat(first.split().developers()).isEqualTo(2);

        var profile = standings.getStandingSnapshots(1L, READER, List.of(now)).getFirst();
        var reviewedProfile = Objects.requireNonNull(profile.practices().get(reviewed.getSlug()));
        var offProfile = Objects.requireNonNull(profile.practices().get(off.getSlug()));
        assertThat(first.yourStanding()).isEqualTo(reviewedProfile.dto().standing());
        var offStanding = offProfile.dto().standing();
        assertThat(StandingScale.isVerdict(offStanding)).isTrue();
        assertThat(row.practices().get(1).yourStanding()).isEqualTo(offStanding);
        assertThat(row.practices().get(1).split().noneYet()).isEqualTo(1);
        assertThat(row.yourStanding())
                .isEqualTo(groupStandings
                        .summarize(List.of(group), profile)
                        .getFirst()
                        .standing());
        assertThat(row.split().developers()).isEqualTo(2);

        // Counted: the reader, the quiet member and the member who joined through their results; never the hidden one.
        assertThat(overview.openFeedback().yours()).isEqualTo(2);
        assertThat(overview.openFeedback().middle()).isEqualTo(new WorkspaceRangeDTO(1, 2));
        assertThat(overview.includesEarlierReviews()).isFalse();
    }

    @Test
    void shouldLeaveTheReaderUnmarkedWhenTheReaderIsNotCounted() {
        result(reviewed, PROLIFIC, 10, 5, ResultOutcome.MET);
        when(currentDeveloper.currentDeveloperId()).thenReturn(Optional.of(HIDDEN));

        var row = service.read(context).groups().getFirst();

        assertThat(row.yourStanding()).isNull();
        assertThat(row.yourBasis()).isNull();
        assertThat(row.practices())
                .allSatisfy(practice -> assertThat(practice.yourStanding()).isNull());
        assertThat(row.split().developers()).isEqualTo(1);
    }

    @Test
    void shouldShowEmptySplitsWhenNobodyHasAStanding() {
        var overview = service.read(context);

        var row = overview.groups().getFirst();
        assertThat(row.split().developers()).isZero();
        assertThat(row.split().noneYet()).isZero();
        assertThat(row.yourStanding()).isNull();
        assertThat(overview.openFeedback().middle()).isEqualTo(new WorkspaceRangeDTO(1, 2));
    }

    @Test
    void shouldReadTheTilesOverTheWindowRatherThanTheLastNinetyDays() {
        result(reviewed, READER, 1, 5, ResultOutcome.MET);
        result(reviewed, PROLIFIC, 10, 60, ResultOutcome.MET);

        var recent = service.readTiles(context, PracticesAcrossWorkspaceWindow.DAYS_30);
        var quarter = service.readTiles(context, PracticesAcrossWorkspaceWindow.DAYS_90);

        assertThat(recent.developersWithAStandingInWindow()).isEqualTo(1);
        assertThat(recent.reviewedWork().middle()).isEqualTo(new WorkspaceRangeDTO(1, 1));
        assertThat(quarter.developersWithAStandingInWindow()).isEqualTo(2);
        assertThat(quarter.yourPractices()).isEqualTo(2);
        assertThat(quarter.practicesGoingWell().yours()).isEqualTo((int)
                standings.getStandingSnapshots(1L, READER, List.of(now)).getFirst().practices().values().stream()
                        .filter(practice -> practice.dto().standing() == PracticeStanding.STRENGTH)
                        .count());
    }

    private static Set<UUID> ids(Collection<Result> rows) {
        return rows.stream().map(Result::getId).collect(Collectors.toSet());
    }

    private void result(Practice practice, long developer, long item, int daysAgo, ResultOutcome outcome) {
        var base = ReviewTestData.view(
                item,
                UUID.randomUUID(),
                now.minus(Duration.ofDays(daysAgo)),
                new ItemKind("scm.pull_request"),
                outcome);
        var row = resultBuilder(base.result())
                .practiceId(practice.getId())
                .practiceVersionId(practice.getCurrentVersion().getId())
                .developerId(developer)
                .build();
        data.add(new ResultView(row, practice, practice.getCurrentVersion(), base.review(), base.item()));
    }

    private static PracticeGroup group(String slug, int position) {
        var group = new PracticeGroup();
        group.setSlug(slug);
        group.setName(slug);
        group.setPosition(position);
        return group;
    }

    private static Practice practice(long id, String slug, PracticeGroup group, int position, ReviewedByKind by) {
        var definition = DefinitionTestFixtures.definition();
        var practice = ReviewTestData.practice(
                1,
                id,
                slug,
                new PracticeDefinition(
                        definition.name(),
                        definition.reviewOn(),
                        definition.appliesWhen(),
                        definition.about(),
                        definition.reads(),
                        new ReviewedBy(by, null, List.of()),
                        definition.criteria(),
                        definition.script(),
                        definition.scan(),
                        definition.lineNotes(),
                        definition.guidance()));
        practice.setGroup(group);
        practice.setPosition(position);
        return practice;
    }
}
