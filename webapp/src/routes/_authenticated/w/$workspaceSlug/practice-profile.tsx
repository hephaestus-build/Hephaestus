import { createFileRoute, Navigate, retainSearchParams } from "@tanstack/react-router";

import type { PracticeGroup } from "@/api/types.gen";
import { combinePanelStates, loadProps, queryLoadState } from "@/components/common/panel-state";
import {
	type DetailStackEntry,
	encodeDetailStack,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { AllPracticesLevel } from "@/components/practice-profile/AllPracticesLevel";
import { composeNextStep, groupOverviewOf } from "@/components/practice-profile/compose-overview";
import {
	allPracticesLevel,
	DEFAULT_FEEDBACK_TAB,
	parsePracticeGroupListSort,
	PRACTICE_PROFILE_LEVEL_KINDS,
	openLevelId,
	PRACTICE_PROFILE_SEARCH_PARAMS,
	type PracticeGroupDetailSelection,
	type PracticeProfileDetailLevelKind,
	practiceGroupLevel,
	practiceLevel,
	practiceProfileSearchSchema,
	type PracticeTab,
} from "@/components/practice-profile/practice-profile-search";
import { PracticeGroupDetailDrawer } from "@/components/practice-profile/PracticeGroupDetailDrawer";
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import { useInAppFeedback } from "@/hooks/use-in-app-feedback";
import { REVIEW_RUN_PAGE_SIZE, usePracticeGroupDetail } from "@/hooks/use-practice-group-detail";
import { usePracticeProfileOverview } from "@/hooks/use-practice-profile-overview";
import { usePracticeStandings } from "@/hooks/use-practice-standings";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { workspaceHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/practice-profile")({
	component: PracticeProfile,
	head: workspaceHead("Practice profile"),
	validateSearch: practiceProfileSearchSchema,
	search: {
		middlewares: [retainSearchParams(PRACTICE_PROFILE_SEARCH_PARAMS)],
	},
});

/** The selection inside the practice level, which leaves the URL with the level. */
const PRACTICE_LEVEL_PARAMS = [
	"practiceTab",
] as const satisfies readonly (keyof PracticeGroupDetailSelection)[];

function PracticeProfile() {
	// A user view reads the developer's page and answers nothing on their behalf.
	const readOnly = useAuth().userView !== undefined;
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const sort = parsePracticeGroupListSort(search);
	const setSearch = useSearchState();

	const detailStack = parseDetailStack(search.detail, PRACTICE_PROFILE_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack, { levelParams: PRACTICE_LEVEL_PARAMS });

	// The selection inside the practice level — its tab — is UI state on the page the reader is
	// already on, so it is written through `useSearchState` in place, keeping the history entry's
	// state: the level was pushed on this entry, and `useDetailStack` reads that stamp to dismiss
	// the level by going back.
	const updateSelection = (selection: PracticeGroupDetailSelection) => {
		void setSearch((previous) => ({ ...previous, ...selection }), { state: true, replace: true });
	};

	const featureState = useWorkspaceFeatures(workspaceSlug);
	const { groups, groupStandings, practiceStandings, practicesByGroup, ...standings } =
		usePracticeStandings(workspaceSlug);
	const { overview, ...overviewQuery } = usePracticeProfileOverview(workspaceSlug);
	// The cards: read here, which is also what delivers the unread ones — so the read waits for a
	// definite "this workspace reviews practices", or it would mark feedback delivered for a reader
	// the answer below is about to send away.
	const feedback = useInAppFeedback({
		workspaceSlug,
		groups,
		enabled: featureState.features?.practicesEnabled === true,
	});
	const detail = usePracticeGroupDetail({
		workspaceSlug,
		groupSlug: openLevelId(detailStack, "practice-group"),
		practiceSlug: openLevelId(detailStack, "practice"),
		practiceStandings,
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
	const load = loadProps(page);

	// Only a definite "off" redirects: the surface exists only where practices review the work, and
	// sending someone away before the answer arrives would bounce them out of a workspace that does.
	if (featureState.features?.practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	const visibleGroups = sortPracticeGroups(groups, groupStandings, sort);

	const openGroup = (group: PracticeGroup) => stackControls.open(practiceGroupLevel(group.slug));
	// One level in, one level out: a practice is two history entries — its group, then the
	// practice over it — so the level's back arrow, which goes back in history, lands on the group
	// and the browser's Back button agrees with it. Pushed in turn, since each entry is written
	// against the location the one before it produced. A list that is open stays underneath.
	const pushLevel = async (
		stack: DetailStackEntry<PracticeProfileDetailLevelKind>[],
		tab?: PracticeTab,
	) =>
		setSearch(
			(previous) => ({
				...previous,
				detail: encodeDetailStack(stack),
				practiceTab: tab,
			}),
			{ state: (previous) => ({ ...previous, detailPush: true }) },
		);
	// A practice whose standing this workspace does not carry opens alone and the level says so.
	const openPractice = async (practiceSlug: string, tab?: PracticeTab) => {
		const groupSlug = practiceStandings.find((entry) => entry.slug === practiceSlug)?.groupSlug;
		const withGroup = hasText(groupSlug)
			? [...detailStack, practiceGroupLevel(groupSlug)]
			: detailStack;
		if (hasText(groupSlug)) {
			await pushLevel(withGroup);
		}
		await pushLevel([...withGroup, practiceLevel(practiceSlug)], tab);
	};

	return (
		<>
			<PracticeProfilePage
				overview={overview}
				practices={practiceStandings}
				groups={groups}
				feedbackCards={feedback.cards}
				ratingProps={readOnly ? undefined : feedback.ratingProps}
				onOpenGroup={openGroup}
				onOpenPractice={(slug, tab) => {
					void openPractice(slug, tab);
				}}
				feedbackTab={search.feedback ?? DEFAULT_FEEDBACK_TAB}
				onFeedbackTabChange={(tab) => {
					// A tab is a view of the page, not a place: rewritten in place, with the default as
					// the URL's silence, so Back still leaves the page.
					void setSearch(
						(previous) => ({
							...previous,
							feedback: tab === DEFAULT_FEEDBACK_TAB ? undefined : tab,
						}),
						{ state: true, replace: true },
					);
				}}
				onShowAllPractices={() => stackControls.open(allPracticesLevel())}
				{...load}
			/>
			<PracticeGroupDetailDrawer
				detail={readOnly ? { ...detail, respond: undefined } : detail}
				detailStack={detailStack}
				levelLabel={() => "All practice groups"}
				renderLevel={(entry, level, path) =>
					entry.kind === "practices" ? (
						<AllPracticesLevel
							nested={level.nested}
							path={path}
							groups={visibleGroups}
							standings={groupStandings}
							practicesByGroup={practicesByGroup}
							sentences={overview.groupSentences}
							sort={sort}
							onSortChange={(next) => {
								// The default sort is the URL's silence, so a header press that lands back
								// on it leaves a clean address. Sorting a level's table is a view of the
								// level, not a place: written in place, keeping the entry's `detailPush`
								// stamp, so Back still dismisses the level in one step.
								void setSearch(
									(previous) => ({
										...previous,
										dir: next === DEFAULT_PRACTICE_GROUP_SORT ? undefined : next,
									}),
									{ state: true, replace: true },
								);
							}}
							onOpenGroup={openGroup}
							openGroupSlug={openLevelId(detailStack, "practice-group")}
							onOpenPractice={(slug) => {
								void openPractice(slug);
							}}
							{...load}
						/>
					) : null
				}
				onClose={stackControls.close}
				groups={groups}
				groupStandings={groupStandings}
				state={page}
				feedbackCards={feedback.cards}
				groupOverview={(groupSlug) => ({
					...groupOverviewOf(overview, groupSlug),
					nextStep: composeNextStep(feedback.cards, groupSlug),
				})}
				ratingProps={readOnly ? undefined : feedback.ratingProps}
				onOpenPractice={(practiceSlug) => stackControls.open(practiceLevel(practiceSlug))}
				practiceTab={search.practiceTab}
				skeletonRows={REVIEW_RUN_PAGE_SIZE}
				onSelectionChange={updateSelection}
			/>
		</>
	);
}
