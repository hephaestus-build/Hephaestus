// The palette this page shares with the practice profile is `webapp/AGENTS.md` § Practice surfaces palette.

import { cn } from "cn";
import type { PracticesAcrossWorkspace, PracticesAcrossWorkspaceTiles } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RangeControls } from "@/components/common/RangeControls";
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
	barsHint,
	GROUPS_LOAD_ERROR,
	tilesHint,
	WINDOW_OPTIONS,
	windowHeading,
} from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { SplitLegend } from "./WorkspaceSplitBar";
import { WorkspaceTiles } from "./WorkspaceTiles";

export interface PracticesAcrossTheWorkspacePageProps {
	/** The splits and the open feedback, which read no window. */
	state: PanelState<{ overview: PracticesAcrossWorkspace }>;
	/** `stale` while the tiles shown are the previous window's and the chosen window's are on their way. */
	tiles: PanelState<{ tiles: PracticesAcrossWorkspaceTiles; stale?: boolean }>;
	window: AcrossWorkspaceWindow;
	onWindowChange: (window: AcrossWorkspaceWindow) => void;
	/** The group whose level is open over the page. */
	openGroupSlug?: string;
	onOpenGroup: (groupSlug: string) => void;
}

/**
 * A view of the workspace, not the reader's profile: the reader's figures beside the workspace's
 * typical range over the chosen window, then every practice group's split by current standing,
 * with the reader only as the You marker. Only the tiles read the window.
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
	const loading = state.status === "loading" || tiles.status === "loading";
	return (
		<PageLayout className="space-y-8">
			<PageHeader
				title="Practices across the workspace"
				description="This page shows where the developers in this workspace stand in each practice group. Your next step is in your Practice profile."
			/>

			{state.status === "error" ? (
				// Every region reads the overview, so no control on the page can fix this but Retry.
				<QueryErrorAlert error={state.error} title={GROUPS_LOAD_ERROR} onRetry={state.onRetry} />
			) : (
				<>
					<Section
						size="lg"
						title="Your figures"
						description={windowHeading(window)}
						aria-busy={loading || stale || undefined}
						actions={
							<RangeControls
								options={WINDOW_OPTIONS}
								range={window}
								onRangeChange={onWindowChange}
								updating={stale}
							/>
						}
					>
						{tiles.status === "error" ? (
							<QueryErrorAlert
								error={tiles.error}
								title="We could not load your figures"
								onRetry={tiles.onRetry}
							/>
						) : (
							<div className="space-y-3">
								<WorkspaceTiles
									tiles={windowTiles}
									openFeedback={overview?.openFeedback}
									stale={stale}
								/>
								{/* The skeleton holds the hint's place, so nothing moves when it arrives. */}
								{windowTiles === undefined || overview === undefined ? (
									<Skeleton className="h-12 w-full max-w-3xl" />
								) : (
									<div
										className={cn(
											"max-w-3xl space-y-1 text-xs text-muted-foreground",
											stale && STALE,
										)}
									>
										{tilesHint(windowTiles, overview.openFeedback).map((line) => (
											<p key={line}>{line}</p>
										))}
									</div>
								)}
							</div>
						)}
					</Section>

					<Section
						size="lg"
						title={ALL_PRACTICE_GROUPS}
						description={overview && barsHint(overview.minimumOthers, "group")}
					>
						<SplitLegend />
						<GroupsTable
							overview={overview}
							openGroupSlug={openGroupSlug}
							onOpenGroup={onOpenGroup}
						/>
					</Section>
				</>
			)}
		</PageLayout>
	);
}

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
			state={overview === undefined ? { status: "loading" } : { status: "ready", rows }}
			openKey={openGroupSlug}
			empty={NO_PRACTICE_GROUPS}
			rowLink={(row) => ({
				text: "Open group",
				onOpen: () => onOpenGroup(row.key),
			})}
		/>
	);
}
