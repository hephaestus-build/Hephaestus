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
import { type PanelState, panelState, queryLoadState } from "@/components/common/panel-state";
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
	ACROSS_WORKSPACE_STALE_MS,
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
	const enabled = featureState.practicesEnabled === true;
	// The overview does not depend on the window. Both queries can start after the feature read.
	const query = useQuery({
		...getPracticesAcrossWorkspaceOptions({ path: { workspaceSlug } }),
		enabled,
		staleTime: ACROSS_WORKSPACE_STALE_MS,
	});
	// One request per window: the server checks each window against its privacy rule on its own.
	const tilesQuery = useQuery({
		...getPracticesAcrossWorkspaceTilesOptions({ path: { workspaceSlug }, query: { window } }),
		enabled,
		placeholderData: keepPreviousData,
		staleTime: ACROSS_WORKSPACE_STALE_MS,
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

	// Both queries wait on the feature read, so its failure is theirs.
	const featureLoad = queryLoadState({ ...featureState, isPending: featureState.isLoading });
	const state: PanelState<{ overview: PracticesAcrossWorkspace }> =
		featureLoad.status === "error"
			? featureLoad
			: panelState(query, (overview) => ({ status: "ready" as const, overview }));
	const tiles: PanelState<{ tiles: PracticesAcrossWorkspaceTiles; stale: boolean }> =
		featureLoad.status === "error"
			? featureLoad
			: panelState(tilesQuery, (data) => ({
					status: "ready" as const,
					tiles: data,
					stale: tilesQuery.isPlaceholderData,
				}));
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
			<DetailDrawerStack stack={stack} size="detailWide" onClose={stackControls.close}>
				{(entry, level) => (
					<WorkspaceGroupLevel
						key={entry.id}
						nested={level.nested}
						path={pathAt(level.depth)}
						state={levelState(state, group)}
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
	state: PanelState<{ overview: PracticesAcrossWorkspace }>,
	group: WorkspaceGroupSplit | undefined,
): WorkspaceGroupLevelState {
	if (state.status !== "ready") {
		return state;
	}
	return group === undefined ? { status: "missing" } : { status: "ready", group };
}
