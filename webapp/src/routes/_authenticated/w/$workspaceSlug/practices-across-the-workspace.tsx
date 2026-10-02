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
import type { AcrossWorkspaceWindow } from "@/components/practices-across-the-workspace/across-workspace-copy";
import { PracticesAcrossTheWorkspacePage } from "@/components/practices-across-the-workspace/PracticesAcrossTheWorkspacePage";
import { useAcrossWorkspaceMemory } from "@/hooks/use-across-workspace-memory";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { workspaceHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";
import { useAuth } from "@/runtime/auth/AuthContext";

const WINDOWS = ["TERM", "DAYS_30", "DAYS_90"] as const satisfies readonly AcrossWorkspaceWindow[];

const searchSchema = z.object({
	window: z.enum(WINDOWS).default("TERM").catch("TERM"),
});

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/practices-across-the-workspace",
)({
	component: PracticesAcrossTheWorkspace,
	head: workspaceHead("Practices across the workspace"),
	validateSearch: searchSchema,
	search: { middlewares: [stripSearchParams({ window: "TERM" })] },
});

function PracticesAcrossTheWorkspace() {
	// A user view reads the developer's page; the administrator has nothing of their own to estimate.
	const readOnly = useAuth().userView !== undefined;
	const { workspaceSlug } = Route.useParams();
	const { window } = Route.useSearch();
	const setSearch = useSearchState();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	// Read only where this workspace reviews practices, and only the window shown: each window is
	// its own request, checked against the five developer rule on its own.
	const query = useQuery({
		...getPracticesAcrossWorkspaceOptions({ path: { workspaceSlug }, query: { window } }),
		enabled: featureState.practicesEnabled === true,
	});
	const groupSlugs = (query.data?.groups ?? []).map((group) => group.groupSlug);
	const memory = useAcrossWorkspaceMemory(workspaceSlug, groupSlugs);

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

	return (
		<PracticesAcrossTheWorkspacePage
			workspaceSlug={workspaceSlug}
			state={state}
			window={window}
			onWindowChange={(next) => {
				void setSearch((previous) => ({ ...previous, window: next }), { replace: true });
			}}
			showWorkspace={memory.showWorkspace}
			onShowWorkspaceChange={memory.setShowWorkspace}
			askFirst={memory.askFirst}
			onAskFirstChange={memory.setAskFirst}
			canEstimate={!readOnly}
			estimates={memory.estimates}
			onEstimate={memory.setEstimate}
			onSkipRest={memory.skip}
			onStartOver={() => memory.forget(groupSlugs)}
		/>
	);
}

/** The page's state while its data is not in: failed with its retry, or still loading. */
function settling(state: LoadState): PanelState<never> {
	return state.status === "error" ? state : { status: "loading" };
}
