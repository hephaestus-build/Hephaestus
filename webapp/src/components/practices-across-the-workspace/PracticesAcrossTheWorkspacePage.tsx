// The palette this page shares with the practice profile is `webapp/AGENTS.md` § Practice surfaces palette.

import type { PracticesAcrossWorkspace, PracticesAcrossWorkspaceTiles } from "@/api/types.gen";
import { RangeControls } from "@/components/activity/RangeControls";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import {
	ALL_PRACTICE_GROUPS,
	NO_PRACTICE_GROUPS,
} from "@/components/practice-profile/practice-profile-search";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { Skeleton } from "@/components/ui/skeleton";

import {
	type AcrossWorkspaceWindow,
	groupsHint,
	PAGE_PURPOSE,
	tilesHint,
	type SplitContext,
	WINDOW_OPTIONS,
	windowHeading,
} from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { SplitLegend } from "./WorkspaceSplitBar";
import { WorkspaceTiles } from "./WorkspaceTiles";

export interface PracticesAcrossTheWorkspacePageProps {
	/** The splits and the open feedback, which read no window. */
	state: PanelState<{ overview: PracticesAcrossWorkspace }>;
	/**
	 * The window's tiles; `stale` while another window's tiles are on their way and the ones shown
	 * are the previous window's.
	 */
	tiles: PanelState<{ tiles: PracticesAcrossWorkspaceTiles; stale?: boolean }>;
	window: AcrossWorkspaceWindow;
	onWindowChange: (window: AcrossWorkspaceWindow) => void;
	/** The group whose practices are open over the page, which its row marks. */
	openGroupSlug?: string;
	/** Opens a group's practices over the page. */
	onOpenGroup: (groupSlug: string) => void;
}

/** What every split on the page is a part of, from the overview. */
export function splitContextOf(overview: PracticesAcrossWorkspace): SplitContext {
	return {
		readerCounted: overview.readerCounted,
		developersWithAStanding: overview.developersWithAStanding,
		minimumOthers: overview.minimumOthers,
	};
}

/**
 * Practices across the workspace, a view of the workspace rather than the reader's profile: the
 * reader's figures beside the middle half of the workspace over the chosen window, laid out as
 * Activity lays out its range, then every practice group beside how the workspace's developers
 * split across it by their current standing, with the reader only as the You marker. A group opens
 * its practices over the page; the reader's own learning is one link away, in their profile.
 */
export function PracticesAcrossTheWorkspacePage({
	state,
	tiles,
	window,
	onWindowChange,
	openGroupSlug,
	onOpenGroup,
}: PracticesAcrossTheWorkspacePageProps) {
	const overview = state.status === "ready" ? state.overview : undefined;
	const windowTiles = tiles.status === "ready" ? tiles.tiles : undefined;
	const stale = tiles.status === "ready" && tiles.stale === true;
	const failure = failureOf(state, tiles);
	return (
		<PageLayout className="space-y-8">
			<PageHeader title="Practices across the workspace" description={PAGE_PURPOSE} />

			<Section
				size="lg"
				title={windowHeading(window)}
				aria-busy={stale || undefined}
				actions={
					<RangeControls
						options={WINDOW_OPTIONS}
						range={window}
						onRangeChange={onWindowChange}
						updating={stale}
					/>
				}
			>
				{failure === undefined ? (
					// The window applies to the tiles alone. The last window's figures are drained of
					// colour until the new heading's own are in.
					<div className="space-y-3">
						<WorkspaceTiles
							tiles={windowTiles}
							openFeedback={overview?.openFeedback}
							stale={stale}
						/>
						{/* The rule's lines, or lines in their place while the tiles load, so nothing moves. */}
						{windowTiles === undefined ? (
							<Skeleton className="h-8 w-full max-w-3xl" />
						) : (
							<p className="text-xs text-muted-foreground">{tilesHint(windowTiles)}</p>
						)}
					</div>
				) : (
					<QueryErrorAlert {...failure} />
				)}
			</Section>

			{state.status !== "error" && (
				<Section
					size="lg"
					title={ALL_PRACTICE_GROUPS}
					description={overview && groupsHint(overview.minimumOthers)}
				>
					<SplitLegend />
					{/* The bars count the current standing, so a new window leaves them as they are. */}
					<GroupsTable
						overview={overview}
						openGroupSlug={openGroupSlug}
						onOpenGroup={onOpenGroup}
					/>
				</Section>
			)}
		</PageLayout>
	);
}

/**
 * What the tiles' section says when a read failed: the whole page's, when the bars and the open
 * feedback failed, else the window's own tiles'.
 */
function failureOf(
	state: PracticesAcrossTheWorkspacePageProps["state"],
	tiles: PracticesAcrossTheWorkspacePageProps["tiles"],
): { error: unknown; title: string; onRetry: () => void } | undefined {
	if (state.status === "error") {
		return { error: state.error, title: "We could not load the workspace", onRetry: state.onRetry };
	}
	if (tiles.status === "error") {
		return { error: tiles.error, title: "We could not load the figures", onRetry: tiles.onRetry };
	}
	return undefined;
}

/** Every practice group, each with its split and the way to open it. */
function GroupsTable({
	overview,
	openGroupSlug,
	onOpenGroup,
}: {
	overview?: PracticesAcrossWorkspace;
	openGroupSlug?: string;
	onOpenGroup: (groupSlug: string) => void;
}) {
	const rows: ComparisonRow[] = (overview?.groups ?? []).map((group) => {
		const { Icon, pill } = getGroupVisual(group.groupIcon, group.groupColor);
		return {
			key: group.groupSlug,
			name: group.groupName,
			subject: <GroupName name={group.groupName} icon={Icon} pill={pill} />,
			yourStanding: group.yourStanding,
			split: group.split,
		};
	});
	return (
		<WorkspaceComparisonTable
			aria-label={ALL_PRACTICE_GROUPS}
			subjectHead="Practice group"
			state={
				overview === undefined
					? { status: "loading" }
					: { status: "ready", rows, context: splitContextOf(overview) }
			}
			openKey={openGroupSlug}
			empty={NO_PRACTICE_GROUPS}
			rowLink={(row) => ({
				text: "Open group",
				onOpen: () => onOpenGroup(row.key),
			})}
		/>
	);
}
