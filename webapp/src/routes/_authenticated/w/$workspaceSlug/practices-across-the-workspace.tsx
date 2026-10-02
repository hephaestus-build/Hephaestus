import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, stripSearchParams } from "@tanstack/react-router";
import { z } from "zod";

import { getPracticesAcrossWorkspaceOptions } from "@/api/@tanstack/react-query.gen";
import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import {
	combinePanelStates,
	type LoadState,
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
import type { AcrossWorkspaceWindow } from "@/components/practices-across-the-workspace/across-workspace-copy";
import {
	PracticesAcrossTheWorkspacePage,
	splitContextOf,
} from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import { WorkspaceGroupLevel } from "@/components/practices-across-the-workspace/WorkspaceGroupLevel";
import { useAcrossWorkspaceMemory } from "@/hooks/use-across-workspace-memory";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { pageHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";

const WINDOWS = ["TERM", "DAYS_30", "DAYS_90"] as const satisfies readonly AcrossWorkspaceWindow[];

/** The one level the page opens over itself: a practice group's practices. */
const LEVEL_KINDS = ["practice-group"] as const;

const searchSchema = z
	.object({
		window: z.enum(WINDOWS).default("TERM").catch("TERM"),
	})
	.extend(detailStackSchema(LEVEL_KINDS).shape);

/** The page under the level, as the first crumb of its path. */
const PAGE_LABEL = "Across the workspace";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/practices-across-the-workspace",
)({
	component: PracticesAcrossTheWorkspace,
	head: pageHead("Practices across the workspace"),
	validateSearch: searchSchema,
	search: { middlewares: [stripSearchParams({ window: "TERM" })] },
});

function PracticesAcrossTheWorkspace() {
	const { workspaceSlug } = Route.useParams();
	const { window, detail } = Route.useSearch();
	const setSearch = useSearchState();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	// Read only where this workspace reviews practices, and only the window shown: each window is
	// its own request, checked against the five developer rule on its own.
	const query = useQuery({
		...getPracticesAcrossWorkspaceOptions({ path: { workspaceSlug }, query: { window } }),
		enabled: featureState.practicesEnabled === true,
	});
	const memory = useAcrossWorkspaceMemory();
	const stack = parseDetailStack(detail, LEVEL_KINDS);
	const stackControls = useDetailStack(stack);

	if (featureState.practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	const loadState = combinePanelStates([
		queryLoadState({ ...featureState, isPending: featureState.isLoading }),
		queryLoadState(query),
	]);
	const state: PanelState<{ overview: PracticesAcrossWorkspace }> =
		loadState.status === "ready" && query.data !== undefined
			? { status: "ready", overview: query.data }
			: settling(loadState);
	const overview = state.status === "ready" ? state.overview : undefined;
	const openGroupSlug = stack[0]?.id;
	const pathAt = levelPathAt(stack, {
		pageLabel: PAGE_LABEL,
		labelOf: () => "Group",
		onClose: stackControls.close,
	});

	return (
		<>
			<PracticesAcrossTheWorkspacePage
				workspaceSlug={workspaceSlug}
				state={state}
				window={window}
				onWindowChange={(next) => {
					void setSearch((previous) => ({ ...previous, window: next }), { replace: true });
				}}
				showWorkspace={memory.showWorkspace}
				onShowWorkspaceChange={memory.setShowWorkspace}
				openGroupSlug={openGroupSlug}
				onOpenGroup={(groupSlug) => stackControls.open({ kind: "practice-group", id: groupSlug })}
			/>
			{/* A failed read leaves no level to show; the page says why. */}
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
						workspaceSlug={workspaceSlug}
						group={overview?.groups.find((group) => group.groupSlug === entry.id)}
						context={overview && splitContextOf(overview)}
						showWorkspace={memory.showWorkspace}
						isLoading={overview === undefined}
					/>
				)}
			</DetailDrawerStack>
		</>
	);
}

/** The page's state while its data is not in: failed with its retry, or still loading. */
function settling(state: LoadState): PanelState<never> {
	return state.status === "error" ? state : { status: "loading" };
}
