import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, redirect, stripSearchParams } from "@tanstack/react-router";
import { z } from "zod";

import type {
	PracticesAcrossWorkspace,
	PracticesAcrossWorkspaceTiles,
	WorkspaceGroupSplit,
} from "@/api/types.gen";
import { type PanelState, panelState, queryLoadState } from "@/components/common/panel-state";
import {
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { levelPathAt } from "@/components/layout/detail-drawer/level-path";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { ALL_GROUPS_FOCUS, isFocusSwitchStep } from "@/components/practice-focus/practice-focus";
import {
	FOCUS_FOOTER_COPY,
	focusSwitchName,
	PracticeFocusFooter,
} from "@/components/practice-focus/PracticeFocusFooter";
import { usePracticeFocusSwitch } from "@/components/practice-focus/use-practice-focus-switch";
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
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";
import { prefetchPageRead } from "@/runtime/tanstack-query/prefetch-page-read";
import { getUserViewSession } from "@/runtime/user-view/session";

import { overviewRead, tilesRead } from "./-practices-across-the-workspace-reads";

/** The reader's own group and practice levels live in their Practice profile, which this level links to. */
const LEVEL_KINDS = ["practice-group"] as const;

const searchSchema = z
	.object({
		window: z.enum(WINDOW_VALUES).default(DEFAULT_WINDOW).catch(DEFAULT_WINDOW),
		// The practice row the group level marks, written by a switch from the reader's profile.
		practice: z.string().min(1).max(200).optional().catch(undefined),
	})
	.extend(detailStackSchema(LEVEL_KINDS).shape);

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/practices-across-the-workspace",
)({
	component: PracticesAcrossTheWorkspace,
	head: pageHead("Practices across the workspace"),
	codeSplitGroupings: [["loader"], ["component"], ["errorComponent"], ["notFoundComponent"]],
	validateSearch: searchSchema,
	search: {
		middlewares: [stripSearchParams({ window: DEFAULT_WINDOW })],
	},
	// The loader reads the validated window from here: parsing the address in the split loader would
	// pull the search schema and the page's copy out of the entry chunk into chunks of their own.
	beforeLoad: ({ search, params }) => {
		// A read-only user view never reads these counts: the server refuses them, so go home first.
		if (getUserViewSession() !== undefined) {
			throw redirect({ to: "/w/$workspaceSlug", params });
		}
		return { figuresWindow: search.window };
	},
	// No loader deps: a window change is a search change on this page, which reads the other window
	// itself and shows the current figures marked stale meanwhile.
	loader: ({ context, params }) => {
		if (context.workspace?.practicesEnabled !== true) {
			return;
		}
		prefetchPageRead(context.queryClient, overviewRead(params.workspaceSlug));
		prefetchPageRead(context.queryClient, tilesRead(params.workspaceSlug, context.figuresWindow));
	},
});

function PracticesAcrossTheWorkspace() {
	const { workspaceSlug } = Route.useParams();
	const { window, detail, practice } = Route.useSearch();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const enabled = featureState.practicesEnabled === true;
	// The splits and the open feedback read no window, so a window change never reads them again.
	const query = useQuery({ ...overviewRead(workspaceSlug), enabled });
	// One request per window. Another window of this workspace stands in, marked stale; another
	// workspace's never does.
	const tilesQuery = useQuery({
		...tilesRead(workspaceSlug, window),
		enabled,
		placeholderData: keepSameSubject({ workspaceSlug }),
	});
	const stack = parseDetailStack(detail, LEVEL_KINDS);
	// The marked practice belongs to the group level and leaves the address with it.
	const stackControls = useDetailStack({
		levelParams: ["practice"],
		replaceOnClose: isFocusSwitchStep,
	});
	// Typed by this page's search, so a misspelt key does not compile.
	const setView = (view: Partial<z.infer<typeof searchSchema>>) => stackControls.setView(view);
	const openGroupSlug = stack[0]?.id;
	const focusSwitch = usePracticeFocusSwitch(workspaceSlug);

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
	// Each level reads its own group: a hand-edited address can stack two.
	const groupOf = (slug: string) => overview?.groups.find((each) => each.groupSlug === slug);
	const pathAt = levelPathAt(stack, {
		pageLabel: ACROSS_THE_WORKSPACE,
		labelOf: (entry) => groupOf(entry.id)?.groupName ?? "Group",
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
				focusFooter={
					<PracticeFocusFooter
						{...FOCUS_FOOTER_COPY.workspaceAllGroups}
						placement="page"
						accessibleLabel={FOCUS_FOOTER_COPY.workspaceAllGroups.label}
						onSwitch={() => focusSwitch.toProfile(ALL_GROUPS_FOCUS)}
					/>
				}
			/>
			{/* Only the overview feeds the level, so a failed tiles read leaves it open. */}
			<DetailDrawerStack
				stack={stack}
				onClose={stackControls.close}
				footer={(entry) => (
					<PracticeFocusFooter
						{...FOCUS_FOOTER_COPY.workspaceGroup}
						accessibleLabel={focusSwitchName(
							FOCUS_FOOTER_COPY.workspaceGroup.label,
							groupOf(entry.id)?.groupName,
						)}
						onSwitch={() => focusSwitch.toProfile({ groupSlug: entry.id })}
					/>
				)}
			>
				{(entry, level) => (
					<WorkspaceGroupLevel
						nested={level.nested}
						path={pathAt(level.depth)}
						state={levelState(state, groupOf(entry.id))}
						focusPracticeSlug={practice}
						onGoToPractice={(practiceSlug) =>
							focusSwitch.toProfile({ groupSlug: entry.id, practiceSlug })
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
