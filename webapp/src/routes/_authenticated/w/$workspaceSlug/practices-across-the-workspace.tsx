import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, stripSearchParams } from "@tanstack/react-router";
import { z } from "zod";

import { getPracticesAcrossWorkspaceOptions } from "@/api/@tanstack/react-query.gen";
import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import {
	combinePanelStates,
	type LoadState,
	loadProps,
	type PanelState,
	queryLoadState,
} from "@/components/common/panel-state";
import {
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import {
	composeGroupOverview,
	composeNextStep,
} from "@/components/practice-profile/compose-overview";
import {
	DEFAULT_PRACTICE_TAB,
	PRACTICE_TABS,
} from "@/components/practice-profile/practice-profile-search";
import { PracticeDetailLevel } from "@/components/practice-profile/PracticeDetailLevel";
import { PracticeGroupDetailLevel } from "@/components/practice-profile/PracticeGroupDetailLevel";
import {
	DEFAULT_WINDOW,
	WINDOW_VALUES,
} from "@/components/practices-across-the-workspace/across-workspace-copy";
import {
	PracticesAcrossTheWorkspacePage,
	splitContextOf,
} from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import {
	WorkspaceGroupLevel,
	type WorkspaceGroupLevelState,
} from "@/components/practices-across-the-workspace/WorkspaceGroupLevel";
import { WorkspaceSplitBar } from "@/components/practices-across-the-workspace/WorkspaceSplitBar";
import { useInAppFeedback } from "@/hooks/use-in-app-feedback";
import { REVIEW_RUN_PAGE_SIZE, usePracticeGroupDetail } from "@/hooks/use-practice-group-detail";
import { usePracticeProfileOverview } from "@/hooks/use-practice-profile-overview";
import { usePracticeStandings } from "@/hooks/use-practice-standings";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { pageHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";
import { useAuth } from "@/runtime/auth/AuthContext";

/**
 * The levels the page opens over itself: a practice group across the workspace, the reader's own
 * group over it, and one practice over either, as the profile drills down. Nothing here leaves for
 * the Practice profile.
 */
const LEVEL_KINDS = ["practice-group", "own-group", "practice"] as const;

/** What only the practice level reads, cleared when the stack changes. */
const LEVEL_PARAMS = ["practiceTab"] as const;

const searchSchema = z
	.object({
		window: z.enum(WINDOW_VALUES).default(DEFAULT_WINDOW).catch(DEFAULT_WINDOW),
		practiceTab: z.enum(PRACTICE_TABS).default(DEFAULT_PRACTICE_TAB).catch(DEFAULT_PRACTICE_TAB),
	})
	.extend(detailStackSchema(LEVEL_KINDS).shape);

/** The page under the levels, as the first crumb of their path. */
const PAGE_LABEL = "Across the workspace";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/practices-across-the-workspace",
)({
	component: PracticesAcrossTheWorkspace,
	head: pageHead("Practices across the workspace"),
	validateSearch: searchSchema,
	search: {
		middlewares: [stripSearchParams({ window: DEFAULT_WINDOW, practiceTab: DEFAULT_PRACTICE_TAB })],
	},
});

function PracticesAcrossTheWorkspace() {
	const { workspaceSlug } = Route.useParams();
	const { window, detail, practiceTab } = Route.useSearch();
	const readOnly = useAuth().userView !== undefined;
	const setSearch = useSearchState();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	// Read only where this workspace reviews practices, and only the window shown: each window is
	// its own request, checked against CohortPrivacyPolicy on its own.
	const query = useQuery({
		...getPracticesAcrossWorkspaceOptions({ path: { workspaceSlug }, query: { window } }),
		enabled: featureState.practicesEnabled === true,
		// A new window keeps the last one's figures on screen, marked busy, until its own are in.
		placeholderData: keepPreviousData,
	});
	const stack = parseDetailStack(detail, LEVEL_KINDS);
	const stackControls = useDetailStack(stack, { levelParams: LEVEL_PARAMS });
	const openGroupSlug = stack.find((entry) => entry.kind === "practice-group")?.id;
	const ownGroupSlug = stack.find((entry) => entry.kind === "own-group")?.id;
	const openPracticeSlug = stack.find((entry) => entry.kind === "practice")?.id;
	// The reader's own levels show their group and practice as the profile does, from the same
	// reads, and only while one is open: reading the cards is what delivers them.
	const ownOpen =
		(openPracticeSlug !== undefined || ownGroupSlug !== undefined) &&
		featureState.practicesEnabled === true;
	const standings = usePracticeStandings(workspaceSlug, { enabled: ownOpen });
	const { overview: profileOverview, ...profileOverviewQuery } = usePracticeProfileOverview(
		workspaceSlug,
		ownGroupSlug !== undefined && featureState.practicesEnabled === true,
	);
	const feedback = useInAppFeedback({
		workspaceSlug,
		groups: standings.groups,
		enabled: ownOpen,
	});
	const practiceDetail = usePracticeGroupDetail({
		workspaceSlug,
		groupSlug: openGroupSlug,
		practiceSlug: openPracticeSlug,
		practiceStandings: standings.practiceStandings,
	});

	if (featureState.practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	const loadState = combinePanelStates([
		queryLoadState({ ...featureState, isPending: featureState.isLoading }),
		queryLoadState(query),
	]);
	const state: PanelState<{ overview: PracticesAcrossWorkspace; stale: boolean }> =
		loadState.status === "ready" && query.data !== undefined
			? { status: "ready", overview: query.data, stale: query.isPlaceholderData }
			: settling(loadState);
	const overview = state.status === "ready" ? state.overview : undefined;
	const group = overview?.groups.find((each) => each.groupSlug === openGroupSlug);
	const pathAt = levelPathAt(stack, {
		pageLabel: PAGE_LABEL,
		labelOf: (entry) => {
			switch (entry.kind) {
				case "practice": {
					return "Practice";
				}
				// The same group again, now through the reader's own profile: the crumb names the lens
				// that changed rather than the group a second time.
				case "own-group": {
					return "Your profile";
				}
				case "practice-group": {
					return group?.groupName ?? "Group";
				}
			}
		},
		onClose: stackControls.close,
	});
	const practiceLoad = loadProps(combinePanelStates([standings.state, feedback.state]));
	const ownGroupLoad = loadProps(
		combinePanelStates([standings.state, feedback.state, profileOverviewQuery.state]),
	);
	const openPractice = (practiceSlug: string) =>
		stackControls.open({ kind: "practice", id: practiceSlug });

	return (
		<>
			<PracticesAcrossTheWorkspacePage
				state={state}
				window={window}
				onWindowChange={(next) => {
					void setSearch((previous) => ({ ...previous, window: next }), { replace: true });
				}}
				openGroupSlug={openGroupSlug}
				onOpenGroup={(groupSlug) => stackControls.open({ kind: "practice-group", id: groupSlug })}
			/>
			{/* A failed read leaves no level to show; the page says why. */}
			<DetailDrawerStack
				stack={state.status === "error" ? [] : stack}
				size="detailWide"
				onClose={stackControls.close}
			>
				{(entry, level) => {
					if (entry.kind === "practice") {
						const split = group?.practices.find((each) => each.practiceSlug === entry.id);
						return (
							<PracticeDetailLevel
								key={entry.id}
								nested={level.nested}
								path={pathAt(level.depth)}
								practice={practiceDetail.practice}
								feed={practiceDetail.feed}
								feedbackCards={feedback.cards}
								ratingProps={readOnly ? undefined : feedback.ratingProps}
								skeletonRows={REVIEW_RUN_PAGE_SIZE}
								onOpenGroup={group && (() => stackControls.close(level.depth))}
								tab={practiceTab}
								onTabChange={(tab) => {
									void setSearch((previous) => ({ ...previous, practiceTab: tab }), {
										replace: true,
									});
								}}
								observations={{
									onRespond: readOnly ? undefined : practiceDetail.respond,
									pendingResponses: practiceDetail.pendingResponses,
								}}
								// Where the group level shows the group's split, the practice shows its own.
								aside={
									overview && split ? (
										<div className="w-full sm:w-88">
											<WorkspaceSplitBar
												split={split.split}
												yourStanding={split.yourStanding}
												showYourWord={false}
												{...splitContextOf(overview)}
											/>
										</div>
									) : undefined
								}
								{...practiceLoad}
							/>
						);
					}
					if (entry.kind === "own-group") {
						return (
							<PracticeGroupDetailLevel
								key={entry.id}
								nested={level.nested}
								path={pathAt(level.depth)}
								group={standings.groups.find((each) => each.slug === entry.id)}
								standing={standings.groupStandings[entry.id]}
								practices={standings.practicesByGroup[entry.id] ?? []}
								{...composeGroupOverview(profileOverview, entry.id)}
								nextStep={composeNextStep(feedback.cards, entry.id)}
								openPracticeSlug={openPracticeSlug}
								onOpenPractice={openPractice}
								{...ownGroupLoad}
							/>
						);
					}
					return (
						<WorkspaceGroupLevel
							key={entry.id}
							nested={level.nested}
							path={pathAt(level.depth)}
							state={levelState(overview, entry.id)}
							onOpenPractice={openPractice}
							onOpenOwnGroup={() => stackControls.open({ kind: "own-group", id: entry.id })}
						/>
					);
				}}
			</DetailDrawerStack>
		</>
	);
}

/** The open group's level: loading with the page, missing from it, or the group and its context. */
function levelState(
	overview: PracticesAcrossWorkspace | undefined,
	groupSlug: string,
): WorkspaceGroupLevelState {
	if (overview === undefined) {
		return { status: "loading" };
	}
	const group = overview.groups.find((each) => each.groupSlug === groupSlug);
	return group === undefined
		? { status: "missing" }
		: { status: "ready", group, context: splitContextOf(overview) };
}

/** The page's state while its data is not in: failed with its retry, or still loading. */
function settling(state: LoadState): PanelState<never> {
	return state.status === "error" ? state : { status: "loading" };
}
