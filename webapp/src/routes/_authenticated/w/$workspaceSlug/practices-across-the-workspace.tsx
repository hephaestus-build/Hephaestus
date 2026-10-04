import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, stripSearchParams, useNavigate } from "@tanstack/react-router";
import { z } from "zod";

import {
	getPracticesAcrossWorkspaceOptions,
	getPracticesAcrossWorkspaceTilesOptions,
} from "@/api/@tanstack/react-query.gen";
import type {
	PracticesAcrossWorkspace,
	PracticesAcrossWorkspaceTiles,
	WorkspaceGroupSplit,
} from "@/api/types.gen";
import {
	combinePanelStates,
	type LoadState,
	type PanelState,
	queryLoadState,
} from "@/components/common/panel-state";
import {
	type DetailStackEntry,
	detailStackKey,
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import {
	practiceGroupLevel,
	practiceLevel,
} from "@/components/practice-profile/practice-profile-search";
import {
	ACROSS_THE_WORKSPACE,
	DEFAULT_WINDOW,
	WINDOW_VALUES,
} from "@/components/practices-across-the-workspace/across-workspace-copy";
import { PracticesAcrossTheWorkspacePage } from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import {
	WorkspaceGroupLevel,
	type WorkspaceGroupLevelState,
} from "@/components/practices-across-the-workspace/WorkspaceGroupLevel";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { pageHead } from "@/lib/page-title";

/** The reader's own group and practice levels live in their Practice profile, which this level links to. */
const LEVEL_KINDS = ["practice-group"] as const;

const searchSchema = z
	.object({
		window: z.enum(WINDOW_VALUES).default(DEFAULT_WINDOW).catch(DEFAULT_WINDOW),
	})
	.extend(detailStackSchema(LEVEL_KINDS).shape);

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/practices-across-the-workspace",
)({
	component: PracticesAcrossTheWorkspace,
	head: pageHead("Practices across the workspace"),
	validateSearch: searchSchema,
	search: {
		middlewares: [stripSearchParams({ window: DEFAULT_WINDOW })],
	},
});

function PracticesAcrossTheWorkspace() {
	const { workspaceSlug } = Route.useParams();
	const { window, detail } = Route.useSearch();
	const navigate = useNavigate();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	// The overview reads no window, so a new window does not refetch it.
	const enabled = featureState.practicesEnabled === true;
	const query = useQuery({
		...getPracticesAcrossWorkspaceOptions({ path: { workspaceSlug } }),
		enabled,
	});
	// One request per window: the server checks each window against its privacy rule on its own.
	const tilesQuery = useQuery({
		...getPracticesAcrossWorkspaceTilesOptions({ path: { workspaceSlug }, query: { window } }),
		enabled,
		placeholderData: keepPreviousData,
	});
	const stack = parseDetailStack(detail, LEVEL_KINDS);
	const stackControls = useDetailStack(stack);
	// Typed by this page's search, so a misspelt key does not compile.
	const setView = (view: Partial<z.infer<typeof searchSchema>>) => stackControls.setView(view);
	const openGroupSlug = stack[0]?.id;
	const goToProfile = (levels: DetailStackEntry[]) => {
		void navigate({
			to: "/w/$workspaceSlug/practice-profile",
			params: { workspaceSlug },
			search: { detail: levels.map(detailStackKey) },
		});
	};

	if (featureState.practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	const featureLoad = queryLoadState({ ...featureState, isPending: featureState.isLoading });
	const loadState = combinePanelStates([featureLoad, queryLoadState(query)]);
	const state: PanelState<{ overview: PracticesAcrossWorkspace }> =
		loadState.status === "ready" && query.data !== undefined
			? { status: "ready", overview: query.data }
			: settling(loadState);
	const tilesLoad = combinePanelStates([featureLoad, queryLoadState(tilesQuery)]);
	const tiles: PanelState<{ tiles: PracticesAcrossWorkspaceTiles; stale: boolean }> =
		tilesLoad.status === "ready" && tilesQuery.data !== undefined
			? { status: "ready", tiles: tilesQuery.data, stale: tilesQuery.isPlaceholderData }
			: settling(tilesLoad);
	const overview = state.status === "ready" ? state.overview : undefined;
	const group = overview?.groups.find((each) => each.groupSlug === openGroupSlug);
	const pathAt = levelPathAt(stack, {
		pageLabel: ACROSS_THE_WORKSPACE,
		labelOf: () => group?.groupName ?? "Group",
		onClose: stackControls.close,
	});

	return (
		<>
			<PracticesAcrossTheWorkspacePage
				state={state}
				tiles={tiles}
				window={window}
				onWindowChange={(next) => setView({ window: next })}
				openGroupSlug={openGroupSlug}
				onOpenGroup={(groupSlug) => stackControls.open({ kind: "practice-group", id: groupSlug })}
			/>
			{/* Only the overview feeds the level, so a failed tiles read leaves it open. */}
			<DetailDrawerStack
				stack={state.status === "error" ? [] : stack}
				size="detailWide"
				onClose={stackControls.close}
			>
				{(entry, level) => (
					<WorkspaceGroupLevel
						key={entry.id}
						nested={level.nested}
						path={pathAt(level.depth)}
						state={levelState(overview, group)}
						onGoToProfile={() => goToProfile([practiceGroupLevel(entry.id)])}
						onGoToPractice={(practiceSlug) =>
							goToProfile([practiceGroupLevel(entry.id), practiceLevel(practiceSlug)])
						}
					/>
				)}
			</DetailDrawerStack>
		</>
	);
}

function levelState(
	overview: PracticesAcrossWorkspace | undefined,
	group: WorkspaceGroupSplit | undefined,
): WorkspaceGroupLevelState {
	if (overview === undefined) {
		return { status: "loading" };
	}
	return group === undefined
		? { status: "missing" }
		: {
				status: "ready",
				group,
				readerCounted: overview.readerCounted,
				minimumOthers: overview.minimumOthers,
			};
}

function settling(state: LoadState): PanelState<never> {
	return state.status === "error" ? state : { status: "loading" };
}
