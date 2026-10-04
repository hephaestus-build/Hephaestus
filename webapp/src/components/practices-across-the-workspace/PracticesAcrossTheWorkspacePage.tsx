// The palette this page shares with the practice profile is `webapp/AGENTS.md` § Practice surfaces palette.

import { cn } from "cn";
import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { RangeControls } from "@/components/activity/RangeControls";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Section } from "@/components/layout/Section";
import { ALL_PRACTICE_GROUPS } from "@/components/practice-profile/practice-profile-search";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { useRevealedRows } from "@/hooks/use-revealed-rows";

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

/** How many practice groups the table lists before it offers more. */
export const GROUPS_PAGE_SIZE = 20;

export interface PracticesAcrossTheWorkspacePageProps {
	/**
	 * The overview; `stale` while another window's tiles are on their way and the ones shown are the
	 * previous window's.
	 */
	state: PanelState<{ overview: PracticesAcrossWorkspace; stale?: boolean }>;
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
	window,
	onWindowChange,
	openGroupSlug,
	onOpenGroup,
}: PracticesAcrossTheWorkspacePageProps) {
	const overview = state.status === "ready" ? state.overview : undefined;
	const stale = state.status === "ready" && state.stale === true;
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
				{state.status === "error" ? (
					<QueryErrorAlert
						error={state.error}
						title="Could not load the workspace"
						onRetry={state.onRetry}
					/>
				) : (
					// The window applies to the tiles alone. The last window's figures are drained of
					// colour until the new heading's own are in.
					<div className={cn("space-y-3", stale && STALE)}>
						<WorkspaceTiles overview={overview} />
						{overview !== undefined && (
							<p className="text-xs text-muted-foreground">
								{tilesHint(
									overview.minimumOthers,
									overview.window,
									overview.developersWithAStandingInWindow,
								)}
							</p>
						)}
					</div>
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

/** Every practice group, a page at a time, each with its split and the way to open it. */
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
	const { shown, ...more } = useRevealedRows(rows, GROUPS_PAGE_SIZE);
	return (
		<WorkspaceComparisonTable
			aria-label={ALL_PRACTICE_GROUPS}
			subjectHead="Practice group"
			state={
				overview === undefined
					? { status: "loading" }
					: { status: "ready", rows: shown, context: splitContextOf(overview), more }
			}
			openKey={openGroupSlug}
			noun="practice groups"
			empty={{
				title: "No practice groups yet",
				description: "Once your workspace sets up practice groups, each one appears here.",
			}}
			rowLink={(row) => ({
				text: "Open group",
				onOpen: () => onOpenGroup(row.key),
			})}
		/>
	);
}
