import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, stripSearchParams, useNavigate } from "@tanstack/react-router";
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
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { pageHead } from "@/lib/page-title";

/**
 * The one level the page opens over itself: a practice group across the workspace. The reader's
 * own group and practices are in their Practice profile, which the level links to.
 */
const LEVEL_KINDS = ["practice-group"] as const;

const searchSchema = z
	.object({
		window: z.enum(WINDOW_VALUES).default(DEFAULT_WINDOW).catch(DEFAULT_WINDOW),
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
		middlewares: [stripSearchParams({ window: DEFAULT_WINDOW })],
	},
});

function PracticesAcrossTheWorkspace() {
	const { workspaceSlug } = Route.useParams();
	const { window, detail } = Route.useSearch();
	const navigate = useNavigate();
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
	const stackControls = useDetailStack(stack);
	// Typed by this page's search, so a misspelt key does not compile.
	const setView = (view: Partial<z.infer<typeof searchSchema>>) => stackControls.setView(view);
	// Every level is a group, so the open one is the first.
	const openGroupSlug = stack[0]?.id;
	// The reader's own learning is in their Practice profile: the level hands over to its levels.
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
		labelOf: () => group?.groupName ?? "Group",
		onClose: stackControls.close,
	});

	return (
		<>
			<PracticesAcrossTheWorkspacePage
				state={state}
				window={window}
				onWindowChange={(next) => setView({ window: next })}
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
						state={levelState(overview, entry.id)}
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
