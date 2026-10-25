import { useQuery } from "@tanstack/react-query";
import {
	createFileRoute,
	Navigate,
	retainSearchParams,
	stripSearchParams,
} from "@tanstack/react-router";

import { getOwnResultOptions } from "@/api/@tanstack/react-query.gen";
import type { GroupRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { combinePanelStates, queryLoadState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { parseDetailStack } from "@/components/layout/detail-drawer/detail-stack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { isFocusSwitchStep } from "@/components/practice-focus/practice-focus";
import { ProfileFocusFooter } from "@/components/practice-focus/profile-focus-footer";
import { usePracticeFocusSwitch } from "@/components/practice-focus/use-practice-focus-switch";
import {
	composeGroupOverview,
	composeOverview,
} from "@/components/practice-profile/compose-overview";
import {
	PRACTICE_PROFILE_LEVEL_KINDS,
	openLevelId,
	PRACTICE_PROFILE_LEVEL_PARAMS,
	PRACTICE_PROFILE_SEARCH_DEFAULTS,
	PRACTICE_PROFILE_SEARCH_PARAMS,
	practiceGroupLevel,
	practiceLevel,
	type PracticeProfileSearch,
	practiceProfileSearchSchema,
	type PracticeTab,
	REVIEW_SELECTION_CLEARED,
	reviewLevel,
} from "@/components/practice-profile/practice-profile-search";
import { PracticeGroupDetailDrawer } from "@/components/practice-profile/PracticeGroupDetailDrawer";
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import { ROW_LINK_CLASS, RowLinkArrow } from "@/components/practice-vocabulary/PracticeTable";
import { ReviewResultDetails, ReviewResultRow } from "@/components/profile/ReviewResultRow";
import type { FeedbackResponseControls } from "@/components/profile/reviews";
import { Skeleton } from "@/components/ui/skeleton";
import { REVIEW_PAGE_SIZE, usePracticeGroupDetail } from "@/hooks/use-practice-group-detail";
import { usePracticePageFeedback } from "@/hooks/use-practice-page-feedback";
import { usePracticeProfileOverview } from "@/hooks/use-practice-profile-overview";
import { usePracticeProfileReviews } from "@/hooks/use-practice-profile-reviews";
import { usePracticeStandings } from "@/hooks/use-practice-standings";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { formatShortDay, formatTime } from "@/lib/dates";
import { pageHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/practice-profile")({
	component: PracticeProfile,
	head: pageHead("Practice profile"),
	validateSearch: practiceProfileSearchSchema,
	search: {
		middlewares: [
			// The shortest address that means the same is the one a reader shares. Outermost, so it
			// also strips the defaults the retain below copies into a link that names no search,
			// such as the sidebar's.
			stripSearchParams(PRACTICE_PROFILE_SEARCH_DEFAULTS),
			retainSearchParams(PRACTICE_PROFILE_SEARCH_PARAMS),
		],
	},
});

function PracticeProfile() {
	// A user view reads the developer's page and answers nothing on their behalf.
	const readOnly = useAuth().userView !== undefined;
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();

	const detailStack = parseDetailStack(search.detail, PRACTICE_PROFILE_LEVEL_KINDS);
	const stackControls = useDetailStack({
		levelParams: PRACTICE_PROFILE_LEVEL_PARAMS,
		replaceOnClose: isFocusSwitchStep,
	});

	const setView = (view: Partial<PracticeProfileSearch>) => stackControls.setView(view);

	const featureState = useWorkspaceFeatures(workspaceSlug);
	const focusSwitch = usePracticeFocusSwitch(workspaceSlug);
	const {
		groups,
		emptyGroups,
		groupStandings,
		practiceStandings,
		reviewedPractices,
		practicesByGroup,
		...standings
	} = usePracticeStandings(workspaceSlug);
	const { overview, ...overviewQuery } = usePracticeProfileOverview(workspaceSlug);
	// The cards: read here, which is also what delivers the unread ones — so the read waits for a
	// definite "this workspace reviews practices", or it would mark feedback delivered for a reader
	// the answer below is about to send away.
	const feedback = usePracticePageFeedback({
		workspaceSlug,
		groups,
		enabled: featureState.practicesEnabled === true,
	});
	const openReviewId = openLevelId(detailStack, "review");
	const openPracticeSlug = openLevelId(detailStack, "practice");
	const selectedObservationId = openPracticeSlug === undefined ? undefined : search.observation;
	const reviews = usePracticeProfileReviews({
		workspaceSlug,
		listOpen: openLevelId(detailStack, "reviews") !== undefined || openReviewId !== undefined,
		reviewId: openReviewId,
		itemKind: search.reviewKind,
		since: search.reviewSince,
		status: search.reviewStatus,
		itemId: search.reviewItem,
		order: search.reviewOrder,
	});
	const detail = usePracticeGroupDetail({
		workspaceSlug,
		groupSlug: openLevelId(detailStack, "practice-group"),
		practiceSlug: openLevelId(detailStack, "practice"),
		practiceStandings,
		reviewedPractices,
	});

	// The page and every level over it show the three together, so they load and fail as one;
	// whether this workspace reviews practices at all is read with them, since without that answer
	// nothing here is known to exist.
	const page = combinePanelStates([
		queryLoadState({ ...featureState, isPending: featureState.isLoading }),
		standings.state,
		overviewQuery.state,
		feedback.state,
	]);

	// Only a definite "off" redirects: the surface exists only where practices review the work, and
	// sending someone away before the answer arrives would bounce them out of a workspace that does.
	if (featureState.practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	const composed = composeOverview(overview);
	// A user view reads the developer's page; it never spends their workspace's budget.
	const onReviewNow = readOnly ? undefined : reviews.onReviewNow;
	const accepted = readOnly ? undefined : reviews.accepted;
	// The new review opens over the list, without the tab and filters chosen on a review.
	const requestedReview = accepted && (
		<ReviewRequestedNotice
			review={
				<DetailStackLink
					entry={reviewLevel(accepted.reviewId)}
					levelSearch={REVIEW_SELECTION_CLEARED}
				/>
			}
		/>
	);
	const openGroup = (group: GroupRef) => stackControls.open(practiceGroupLevel(group.slug));
	const groupOf = (practiceSlug: string) => {
		const groupSlug = practiceStandings.find((entry) => entry.slug === practiceSlug)?.groupSlug;
		return groups.find((group) => group.slug === groupSlug);
	};
	// One level in, one level out: a practice is its group, then the practice over it, so the
	// level's back arrow lands on the group and the browser's Back button agrees with it. A list
	// that is open stays underneath. A practice whose standing this workspace does not carry opens
	// alone and the level says so. A tab asked for, such as a card's "Learn more" asking for the
	// guide, is the practice level's own search; without one the level opens on its default tab.
	const openPractice = (practiceSlug: string, practiceTab?: PracticeTab) => {
		const groupSlug = practiceStandings.find((entry) => entry.slug === practiceSlug)?.groupSlug;
		void stackControls.push(
			[...(hasText(groupSlug) ? [practiceGroupLevel(groupSlug)] : []), practiceLevel(practiceSlug)],
			practiceTab === undefined ? undefined : { practiceTab },
		);
	};

	return (
		<>
			<PracticeProfilePage
				latestReview={composed.latestReview}
				practices={practiceStandings}
				groups={groups}
				feedbackCards={feedback.cards}
				ratingProps={readOnly ? undefined : feedback.ratingProps}
				onOpenGroup={openGroup}
				onOpenPractice={openPractice}
				feedbackTab={search.feedback}
				onFeedbackTabChange={(tab) => setView({ feedback: tab })}
				state={page}
			/>
			<PracticeGroupDetailDrawer
				detail={readOnly ? { ...detail, responses: undefined } : detail}
				detailStack={detailStack}
				allPracticeGroups={{
					practicesByGroup,
					sentences: composed.groupSentences,
					sort: search.dir,
					onSortChange: (dir) => setView({ dir }),
					onOpenGroup: openGroup,
					onOpenPractice: (slug) => openPractice(slug),
				}}
				onClose={stackControls.close}
				groups={groups}
				emptyGroups={emptyGroups}
				groupStandings={groupStandings}
				state={page}
				feedbackCards={feedback.cards}
				groupOverview={(groupSlug) => composeGroupOverview(overview, groupSlug)}
				ratingProps={readOnly ? undefined : feedback.ratingProps}
				onOpenPractice={(practiceSlug) => stackControls.open(practiceLevel(practiceSlug))}
				practiceTab={search.practiceTab}
				renderObservation={(result) => (
					<OwnObservationDetail
						workspaceSlug={workspaceSlug}
						resultId={result.id}
						practiceSlug={result.practiceSlug}
						responses={readOnly ? undefined : detail.responses}
						layout="body"
					/>
				)}
				selectedObservationId={selectedObservationId}
				selectedObservation={
					selectedObservationId !== undefined && openPracticeSlug !== undefined ? (
						<OwnObservationDetail
							workspaceSlug={workspaceSlug}
							resultId={selectedObservationId}
							practiceSlug={openPracticeSlug}
							responses={readOnly ? undefined : detail.responses}
							layout="row"
						/>
					) : undefined
				}
				skeletonRows={REVIEW_PAGE_SIZE}
				reviews={{
					list: {
						feed: reviews.list,
						onOpenReview: (reviewId) => stackControls.open(reviewLevel(reviewId)),
						kind: search.reviewKind,
						onKindChange: (reviewKind) => setView({ reviewKind }),
						since: search.reviewSince,
						onSinceChange: (reviewSince) => setView({ reviewSince }),
						status: search.reviewStatus,
						onStatusChange: (reviewStatus) => setView({ reviewStatus }),
						order: search.reviewOrder,
						onOrderChange: (reviewOrder) => setView({ reviewOrder }),
						total: reviews.total,
						trackedItemId: search.reviewItem,
						onTrack: (item) => setView({ reviewItem: item?.id }),
						onReviewNow,
						requesting: reviews.requesting,
						// Only while no review is open over the list: that review says its own answer.
						requestOutcome: openReviewId === undefined && requestedReview,
					},
					open: {
						state: reviews.open,
						filters: {
							group: search.reviewGroup,
							practice: search.reviewPractice,
							outcome: search.reviewOutcome,
						},
						onFiltersChange: (next) =>
							setView({
								reviewGroup: next.group,
								reviewPractice: next.practice,
								reviewOutcome: next.outcome,
							}),
						observationLink: (result) => (
							<InlineLink
								className={ROW_LINK_CLASS}
								aria-label={`Open observation for ${result.practiceName}`}
								render={
									<DetailStackLink
										entry={practiceLevel(result.practiceSlug)}
										levelSearch={{
											...REVIEW_SELECTION_CLEARED,
											practiceTab: "results",
											observation: result.id,
										}}
									/>
								}
							>
								Open observation
								<RowLinkArrow />
							</InlineLink>
						),
						groupOf,
						onOpenPractice: openPractice,
					},
				}}
				onSelectionChange={setView}
				// The way to the same group or practice across the workspace. Not in a user view,
				// where that page would count the viewer instead of the developer.
				levelFooter={
					readOnly
						? undefined
						: (entry) => (
								<ProfileFocusFooter
									entry={entry}
									stack={detailStack}
									groupOf={(slug) => groupOf(slug)?.slug}
									nameOf={(focus) =>
										focus.practiceSlug === undefined
											? groups.find((each) => each.slug === focus.groupSlug)?.name
											: practiceStandings.find((each) => each.slug === focus.practiceSlug)?.name
									}
									onSwitch={focusSwitch.toWorkspace}
								/>
							)
				}
			/>
		</>
	);
}

function OwnObservationDetail({
	workspaceSlug,
	resultId,
	practiceSlug,
	responses,
	layout,
}: {
	workspaceSlug: string;
	resultId: string;
	practiceSlug: string;
	responses?: FeedbackResponseControls;
	layout: "row" | "body";
}) {
	const result = useQuery(getOwnResultOptions({ path: { workspaceSlug, resultId } }));
	if (result.isError) {
		return (
			<QueryErrorAlert
				error={result.error}
				title="We could not load this observation"
				onRetry={() => {
					void result.refetch();
				}}
			/>
		);
	}
	if (result.isPending) {
		return (
			<div aria-busy="true" className="flex flex-col gap-3">
				<span className="sr-only">Loading observation…</span>
				<Skeleton className="h-5 w-3/4" />
				<Skeleton className="h-16 w-full" />
				<Skeleton className="h-16 w-full" />
			</div>
		);
	}
	// The own endpoint owns authorization. The destination also checks identity, so a stale or
	// incorrectly routed response never places another practice's words under this practice.
	if (result.data.id !== resultId || result.data.practiceSlug !== practiceSlug) {
		return (
			<p className="text-sm">
				This observation does not belong to this practice. Open it from its review.
			</p>
		);
	}
	return layout === "body" ? (
		<ReviewResultDetails result={result.data} responses={responses} />
	) : (
		<ol
			aria-label="Selected observation"
			className="overflow-hidden rounded-xl border bg-background"
		>
			<ReviewResultRow
				result={result.data}
				defaultOpen
				responses={responses}
				details={<ReviewResultDetails result={result.data} responses={responses} />}
				work={
					<span className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
						{hasText(result.data.item.url) ? (
							<InlineLink href={result.data.item.url} external>
								{result.data.item.label}
							</InlineLink>
						) : (
							result.data.item.label
						)}
						<span>{result.data.item.container}</span>
						<time dateTime={result.data.recordedAt.toISOString()}>
							{formatShortDay(result.data.recordedAt)} {formatTime(result.data.recordedAt)}
						</time>
					</span>
				}
			/>
		</ol>
	);
}
