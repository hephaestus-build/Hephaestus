import { skipToken, type UseQueryResult, useQuery } from "@tanstack/react-query";
import {
	createFileRoute,
	Link,
	Navigate,
	retainSearchParams,
	stripSearchParams,
} from "@tanstack/react-router";

import { getOwnResultOptions } from "@/api/@tanstack/react-query.gen";
import type { GroupRef, ResultDetail, ResultList } from "@/api/types.gen";
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
	type ObservationFilter,
	observationLinkFilter,
	type SelectedObservationState,
} from "@/components/practice-profile/observation-filter";
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
import {
	ObservationDetailSkeleton,
	ReviewResultDetails,
} from "@/components/profile/ReviewResultRow";
import { buttonVariants } from "@/components/ui/button";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { REVIEW_PAGE_SIZE, usePracticeGroupDetail } from "@/hooks/use-practice-group-detail";
import { usePracticePageFeedback } from "@/hooks/use-practice-page-feedback";
import { usePracticeProfileOverview } from "@/hooks/use-practice-profile-overview";
import { usePracticeProfileReviews } from "@/hooks/use-practice-profile-reviews";
import { usePracticeStandings } from "@/hooks/use-practice-standings";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
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
	// From the reader's own membership: an admin read here would ask an admin for a passkey step-up.
	const { isAdmin } = useWorkspaceAccess();
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
	const workspaceName =
		useActiveWorkspaceSlug().workspaces.find((each) => each.workspaceSlug === workspaceSlug)
			?.displayName ?? workspaceSlug;
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
	// The practice level's filter. The observation a link asked for is read on its own, because its
	// review may sit on a page of the feed that has not loaded.
	const observationFilter: ObservationFilter = {
		query: search.observationQuery,
		outcome: search.observationOutcome,
		since: search.observationSince,
		workId: search.observationWork,
		observationId: openPracticeSlug === undefined ? undefined : search.observation,
	};
	const askedObservationOptions = getOwnResultOptions({
		path: { workspaceSlug, resultId: observationFilter.observationId ?? "" },
	});
	const askedObservation = useQuery({
		...askedObservationOptions,
		queryFn:
			observationFilter.observationId === undefined ? skipToken : askedObservationOptions.queryFn,
	});
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
		since: search.observationSince,
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
				firstRun={
					overview.reviewReadiness === null
						? undefined
						: {
								workspaceName,
								readiness: overview.reviewReadiness,
								activeReview: overview.activeReview ?? undefined,
								requestReviewLink: readOnly ? undefined : (
									<Link
										to="/w/$workspaceSlug/activity"
										params={{ workspaceSlug }}
										className={buttonVariants({ variant: "outline", size: "sm" })}
									>
										Request a review on Activity
									</Link>
								),
								aiChoiceLink: readOnly
									? undefined
									: (label) => (
											<Link
												to="/w/$workspaceSlug/onboarding"
												params={{ workspaceSlug }}
												search={{ returnTo: `/w/${workspaceSlug}/practice-profile` }}
												className={buttonVariants({ variant: "outline", size: "sm" })}
											>
												{label}
											</Link>
										),
								adminLinks: isAdmin
									? {
											setupSummary: (label) => (
												<InlineLink
													render={
														<Link
															to="/w/$workspaceSlug/admin/settings"
															params={{ workspaceSlug }}
														/>
													}
												>
													{label}
												</InlineLink>
											),
											reviewScope: (label) => (
												<InlineLink
													render={
														<Link
															to="/w/$workspaceSlug/admin/practices/review"
															params={{ workspaceSlug }}
															search={{ section: "automated-review" }}
														/>
													}
												>
													{label}
												</InlineLink>
											),
										}
									: undefined,
							}
				}
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
				markedFeedbackId={search.feedbackCard}
				renderObservation={(result) => (
					<OwnObservationDetail
						workspaceSlug={workspaceSlug}
						resultId={result.id}
						practiceSlug={result.practiceSlug}
					/>
				)}
				observationFilter={observationFilter}
				selectedObservation={selectedObservationState(
					askedObservation,
					observationFilter.observationId,
					openPracticeSlug,
				)}
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
											...observationLinkSearch(result),
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

/**
 * The asked observation's state for the practice level: loading, failed, read, or one of another
 * practice. The own endpoint owns authorization; the level also checks identity, so a stale or
 * misrouted answer never shows another practice's words under this practice.
 */
function selectedObservationState(
	query: UseQueryResult<ResultDetail>,
	resultId: string | undefined,
	practiceSlug: string | undefined,
): SelectedObservationState | undefined {
	if (resultId === undefined || practiceSlug === undefined) {
		return undefined;
	}
	if (query.isError) {
		return {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	}
	if (query.isPending) {
		return { status: "loading" };
	}
	return query.data.id === resultId && query.data.practiceSlug === practiceSlug
		? { status: "ready", result: query.data }
		: { status: "elsewhere" };
}

/** A review row's "Open observation": the practice level filtered to its work, with it open. */
function observationLinkSearch(result: ResultList) {
	const filter = observationLinkFilter(result);
	return {
		observation: filter.observationId,
		observationQuery: filter.query,
		observationWork: filter.workId,
	};
}

function OwnObservationDetail({
	workspaceSlug,
	resultId,
	practiceSlug,
}: {
	workspaceSlug: string;
	resultId: string;
	practiceSlug: string;
}) {
	// The asked observation's own read fills this cache entry first; opening its row reuses it.
	const result = useQuery({
		...getOwnResultOptions({ path: { workspaceSlug, resultId } }),
		refetchOnMount: false,
	});
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
		return <ObservationDetailSkeleton />;
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
	return (
		<ReviewResultDetails
			result={result.data}
			feedbackLinks={{
				// The practice level this observation is open in: its Feedback tab holds the card. The
				// observation stays the Observations tab's filter, as a review's link sets it, so the way
				// back to the tab finds it open again.
				practicePage: (feedbackId) => (
					<Link
						to="."
						search={(previous) => ({
							...previous,
							...observationLinkSearch(result.data),
							practiceTab: "feedback",
							// The card this observation's feedback became, which the tab shows and marks.
							feedbackCard: feedbackId,
						})}
					/>
				),
				conversation: <Link to="/w/$workspaceSlug/conversation" params={{ workspaceSlug }} />,
			}}
		/>
	);
}
