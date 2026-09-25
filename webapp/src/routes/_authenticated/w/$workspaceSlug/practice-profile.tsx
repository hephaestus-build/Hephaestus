import {
	createFileRoute,
	Navigate,
	retainSearchParams,
	stripSearchParams,
} from "@tanstack/react-router";

import type { PracticeGroup } from "@/api/types.gen";
import { combinePanelStates, queryLoadState } from "@/components/common/panel-state";
import { parseDetailStack } from "@/components/layout/detail-drawer/detail-stack";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import {
	composeGroupOverview,
	composeNextStep,
	composeOverview,
} from "@/components/practice-profile/compose-overview";
import {
	PRACTICE_PROFILE_LEVEL_KINDS,
	openLevelId,
	PRACTICE_PROFILE_SEARCH_DEFAULTS,
	PRACTICE_PROFILE_SEARCH_PARAMS,
	type PracticeGroupDetailSelection,
	practiceGroupLevel,
	practiceLevel,
	type PracticeProfileSearch,
	practiceProfileSearchSchema,
	type PracticeTab,
} from "@/components/practice-profile/practice-profile-search";
import { PracticeGroupDetailDrawer } from "@/components/practice-profile/PracticeGroupDetailDrawer";
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
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
		middlewares: [
			retainSearchParams(PRACTICE_PROFILE_SEARCH_PARAMS),
			// The shortest address that means the same is the one a reader shares.
			stripSearchParams(PRACTICE_PROFILE_SEARCH_DEFAULTS),
		],
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
	const setSearch = useSearchState();

	const detailStack = parseDetailStack(search.detail, PRACTICE_PROFILE_LEVEL_KINDS);
	const stackControls = useDetailStack(detailStack, { levelParams: PRACTICE_LEVEL_PARAMS });

	// A tab or a sort is a view of what is open, not a place: rewritten in place, keeping the history
	// entry's state — the stamp `useDetailStack` reads to dismiss a level by going back.
	const setView = (view: Partial<PracticeProfileSearch>) => {
		void setSearch((previous) => ({ ...previous, ...view }), { state: true, replace: true });
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

	// Only a definite "off" redirects: the surface exists only where practices review the work, and
	// sending someone away before the answer arrives would bounce them out of a workspace that does.
	if (featureState.features?.practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	const composed = composeOverview(overview);
	const openGroup = (group: PracticeGroup) => stackControls.open(practiceGroupLevel(group.slug));
	// One level in, one level out: a practice is its group, then the practice over it, so the
	// level's back arrow lands on the group and the browser's Back button agrees with it. A list
	// that is open stays underneath. A practice whose standing this workspace does not carry opens
	// alone and the level says so.
	const openPractice = (practiceSlug: string, tab?: PracticeTab) => {
		const groupSlug = practiceStandings.find((entry) => entry.slug === practiceSlug)?.groupSlug;
		void stackControls.push(
			[...(hasText(groupSlug) ? [practiceGroupLevel(groupSlug)] : []), practiceLevel(practiceSlug)],
			{ practiceTab: tab },
		);
	};

	return (
		<>
			<PracticeProfilePage
				overview={composed}
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
				detail={readOnly ? { ...detail, respond: undefined } : detail}
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
				groupStandings={groupStandings}
				state={page}
				feedbackCards={feedback.cards}
				groupOverview={(groupSlug) => ({
					...composeGroupOverview(overview, groupSlug),
					nextStep: composeNextStep(feedback.cards, groupSlug),
				})}
				ratingProps={readOnly ? undefined : feedback.ratingProps}
				onOpenPractice={(practiceSlug) => stackControls.open(practiceLevel(practiceSlug))}
				practiceTab={search.practiceTab}
				skeletonRows={REVIEW_RUN_PAGE_SIZE}
				onSelectionChange={setView}
			/>
		</>
	);
}
