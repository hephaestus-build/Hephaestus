import { createFileRoute, retainSearchParams } from "@tanstack/react-router";
import { z } from "zod";

import type { PracticeGroup } from "@/api/types.gen";
import { combinePanelStates, loadProps } from "@/components/common/panel-state";
import {
	type DetailStackEntry,
	encodeDetailStack,
	parseDetailStack,
} from "@/components/core/detail-drawer/detail-stack";
import { useDetailStack } from "@/components/core/detail-drawer/use-detail-stack";
import { AllPracticesLevel } from "@/components/practice-profile/AllPracticesLevel";
import { composeNextStep, groupOverviewOf } from "@/components/practice-profile/compose-overview";
import {
	allPracticesLevel,
	DEFAULT_FEEDBACK_TAB,
	feedbackTabSearchSchema,
	parsePracticeGroupListSort,
	PRACTICE_PROFILE_LEVEL_KINDS,
	PRACTICE_PROFILE_SEARCH_PARAMS,
	type PracticeGroupDetailSelection,
	type PracticeProfileDetailLevelKind,
	practiceGroupLevel,
	practiceGroupListSearchSchema,
	practiceLevel,
	practiceProfileDetailSearchShape,
	type PracticeTab,
} from "@/components/practice-profile/practice-profile-search";
import { PracticeGroupDetailDrawer } from "@/components/practice-profile/PracticeGroupDetailDrawer";
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	sortPracticeGroups,
} from "@/components/practice-vocabulary/practice-group-list-order";
import { useInAppFeedback } from "@/hooks/use-in-app-feedback";
import { usePracticeGroupDetail } from "@/hooks/use-practice-group-detail";
import { usePracticeProfileOverview } from "@/hooks/use-practice-profile-overview";
import { usePracticeStandings } from "@/hooks/use-practice-standings";
import { workspaceHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";

const practiceProfileSearchSchema = z.object({
	...practiceGroupListSearchSchema.shape,
	...feedbackTabSearchSchema.shape,
	...practiceProfileDetailSearchShape,
});

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/practice-profile")({
	component: PracticeProfile,
	head: workspaceHead("Practice profile"),
	validateSearch: practiceProfileSearchSchema,
	search: {
		middlewares: [retainSearchParams([...PRACTICE_PROFILE_SEARCH_PARAMS])],
	},
});

/** The selection inside the practice level, which leaves the URL with the level. */
const PRACTICE_LEVEL_PARAMS = ["practiceTab"] as const satisfies ReadonlyArray<
	keyof PracticeGroupDetailSelection
>;

function PracticeProfile() {
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
	const updateSelection = (selection: PracticeGroupDetailSelection) =>
		void setSearch((previous) => ({ ...previous, ...selection }), { state: true, replace: true });

	const { groups, groupStandings, practiceStandings, practicesByGroup, ...standings } =
		usePracticeStandings(workspaceSlug);
	const { overview, ...overviewQuery } = usePracticeProfileOverview(workspaceSlug);
	// The cards: read here, which is also what delivers the unread ones.
	const feedback = useInAppFeedback({ workspaceSlug, groups });
	const detail = usePracticeGroupDetail({
		workspaceSlug,
		groupSlug: detailStack.find((entry) => entry.kind === "practice-group")?.id,
		practiceSlug: detailStack.find((entry) => entry.kind === "practice")?.id,
		practiceStandings,
	});

	// The page and every level over it show the three together, so they load and fail as one.
	const page = combinePanelStates([standings.state, overviewQuery.state, feedback.state]);
	const load = loadProps(page);

	const visibleGroups = sortPracticeGroups(groups, groupStandings, sort);

	const openGroup = (group: PracticeGroup) => stackControls.open(practiceGroupLevel(group.slug));
	// One level in, one level out: a practice is two history entries — its group, then the
	// practice over it — so the level's back arrow, which goes back in history, lands on the group
	// and the browser's Back button agrees with it. Pushed in turn, since each entry is written
	// against the location the one before it produced. A list that is open stays underneath.
	const pushLevel = (
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
		const withGroup = groupSlug ? [...detailStack, practiceGroupLevel(groupSlug)] : detailStack;
		if (groupSlug) await pushLevel(withGroup);
		await pushLevel([...withGroup, practiceLevel(practiceSlug)], tab);
	};

	return (
		<>
			<PracticeProfilePage
				overview={overview}
				practices={practiceStandings}
				groups={groups}
				feedbackCards={feedback.cards}
				ratingProps={feedback.ratingProps}
				onOpenGroup={openGroup}
				onOpenPractice={(slug, tab) => void openPractice(slug, tab)}
				feedbackTab={search.feedback ?? DEFAULT_FEEDBACK_TAB}
				onFeedbackTabChange={(tab) =>
					// A tab is a view of the page, not a place: rewritten in place, with the default as
					// the URL's silence, so Back still leaves the page.
					void setSearch(
						(previous) => ({
							...previous,
							feedback: tab === DEFAULT_FEEDBACK_TAB ? undefined : tab,
						}),
						{ state: true, replace: true },
					)
				}
				onShowAllPractices={() => stackControls.open(allPracticesLevel())}
				{...load}
			/>
			<PracticeGroupDetailDrawer
				detail={detail}
				detailStack={detailStack}
				levelLabel={() => "All practices"}
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
							onSortChange={(next) =>
								// The default sort is the URL's silence, so a header press that lands back
								// on it leaves a clean address.
								void setSearch((previous) => ({
									...previous,
									dir:
										next.direction === DEFAULT_PRACTICE_GROUP_SORT.direction
											? undefined
											: next.direction,
								}))
							}
							onOpenGroup={openGroup}
							openGroupSlug={
								detailStack.find((candidate) => candidate.kind === "practice-group")?.id
							}
							onOpenPractice={(slug) => void openPractice(slug)}
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
				ratingProps={feedback.ratingProps}
				onOpenPractice={(practiceSlug) => stackControls.open(practiceLevel(practiceSlug))}
				practiceTab={search.practiceTab}
				onSelectionChange={updateSelection}
			/>
		</>
	);
}
