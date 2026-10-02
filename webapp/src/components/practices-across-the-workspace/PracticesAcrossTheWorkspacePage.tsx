// The palette this page shares with the practice profile is `webapp/AGENTS.md` § Practice surfaces palette.
import { useId } from "react";

import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import { FilterToggle } from "@/components/common/FilterToggle";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { GroupName } from "@/components/practice-vocabulary/GroupName";
import { Field, FieldContent, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { useRevealedRows } from "@/hooks/use-revealed-rows";

import {
	type AcrossWorkspaceWindow,
	QUANTILE_NOTE,
	type SplitContext,
	WINDOW_OPTIONS,
	windowHeading,
	windowPhrase,
} from "./across-workspace-copy";
import { ProfileLevelLink } from "./ProfileLevelLink";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { WorkspaceTiles } from "./WorkspaceTiles";

/** How many practice groups the table lists before it shows more as the reader reaches the end. */
export const GROUPS_PAGE_SIZE = 20;

export const ALL_PRACTICE_GROUPS = "All practice groups";

export interface PracticesAcrossTheWorkspacePageProps {
	workspaceSlug: string;
	state: PanelState<{ overview: PracticesAcrossWorkspace }>;
	window: AcrossWorkspaceWindow;
	onWindowChange?: (window: AcrossWorkspaceWindow) => void;
	/** Whether the workspace is shown beside the reader's own standing; remembered by the route. */
	showWorkspace: boolean;
	onShowWorkspaceChange?: (show: boolean) => void;
	/** The group whose practices are open over the page, which its row marks. */
	openGroupSlug?: string;
	/** Opens a group's practices over the page. */
	onOpenGroup?: (groupSlug: string) => void;
}

/** What every split on the page is a part of, from the overview. */
export function splitContextOf(overview: PracticesAcrossWorkspace): SplitContext {
	return {
		window: overview.window,
		readerCounted: overview.readerCounted,
		observedDevelopers: overview.observedDevelopers,
		minimumOthers: overview.minimumOthers,
	};
}

/**
 * Practices across the workspace: the reader's figures beside the middle half of the workspace,
 * then every practice group beside how the workspace's observed developers split across it. A
 * group opens its practices over the page, and every way out leads to the reader's own profile.
 */
export function PracticesAcrossTheWorkspacePage({
	workspaceSlug,
	state,
	window,
	onWindowChange,
	showWorkspace,
	onShowWorkspaceChange,
	openGroupSlug,
	onOpenGroup,
}: PracticesAcrossTheWorkspacePageProps) {
	const overview = state.status === "ready" ? state.overview : undefined;
	return (
		<div className="flex flex-col gap-8">
			<header className="flex flex-col gap-4">
				<div className="flex min-w-0 flex-col gap-2">
					<h1 className="text-2xl font-semibold tracking-tight">Practices across the workspace</h1>
					<p className="text-sm text-muted-foreground">{QUANTILE_NOTE}</p>
					<Coverage
						overview={overview}
						showWorkspace={showWorkspace}
						isLoading={state.status === "loading"}
					/>
				</div>
				<WorkspaceSwitch
					window={window}
					showWorkspace={showWorkspace}
					onShowWorkspaceChange={onShowWorkspaceChange}
				/>
			</header>

			<section aria-labelledby="window-heading" className="flex flex-col gap-3">
				<div className="flex flex-wrap items-center justify-between gap-3">
					<h2 id="window-heading" className="text-lg font-semibold tracking-tight">
						{windowHeading(window)}
					</h2>
					<FilterToggle
						label="Time range"
						options={WINDOW_OPTIONS}
						value={window}
						onChange={(next) => onWindowChange?.(next)}
					/>
				</div>
				{state.status === "error" ? (
					<QueryErrorAlert
						error={state.error}
						title="Couldn't load the workspace"
						onRetry={state.onRetry}
					/>
				) : (
					<WorkspaceTiles overview={overview} showWorkspace={showWorkspace} />
				)}
			</section>

			{state.status !== "error" && (
				<section aria-labelledby="groups-heading" className="flex flex-col gap-3">
					<h2 id="groups-heading" className="text-lg font-semibold tracking-tight">
						{ALL_PRACTICE_GROUPS}
					</h2>
					<GroupsTable
						key={overview?.window}
						workspaceSlug={workspaceSlug}
						overview={overview}
						showWorkspace={showWorkspace}
						openGroupSlug={openGroupSlug}
						onOpenGroup={onOpenGroup}
					/>
				</section>
			)}
		</div>
	);
}

function Coverage({
	overview,
	showWorkspace,
	isLoading,
}: {
	overview?: PracticesAcrossWorkspace;
	showWorkspace: boolean;
	isLoading: boolean;
}) {
	if (overview === undefined) {
		return isLoading ? <Skeleton className="h-5 w-80" /> : null;
	}
	return (
		<p className="text-sm text-muted-foreground">
			{showWorkspace && (
				<>
					<span className="font-semibold text-foreground tabular-nums">
						{overview.observedDevelopers} of {overview.eligibleDevelopers} developers
					</span>{" "}
					observed,{" "}
				</>
			)}
			<span className="font-semibold text-foreground tabular-nums">
				{overview.reviewedWork.yours}
			</span>{" "}
			{overview.reviewedWork.yours === 1 ? "piece" : "pieces"} of your work reviewed,{" "}
			{windowPhrase(overview.window)}.
		</p>
	);
}

function WorkspaceSwitch({
	window,
	showWorkspace,
	onShowWorkspaceChange,
}: {
	window: AcrossWorkspaceWindow;
	showWorkspace: boolean;
	onShowWorkspaceChange?: (show: boolean) => void;
}) {
	const id = useId();
	return (
		<div className="flex flex-col gap-3 rounded-xl border bg-sidebar px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
			<p className="max-w-[70ch] text-sm text-muted-foreground">
				Shown so you can see which practice groups the developers in this workspace reach{" "}
				{windowPhrase(window)}, and so what is within reach for you. Remembered for you; turn it off
				at any time.
			</p>
			<Field orientation="horizontal" className="shrink-0">
				<Switch
					id={`${id}-show`}
					checked={showWorkspace}
					onCheckedChange={(checked) => onShowWorkspaceChange?.(checked)}
				/>
				<FieldContent>
					<FieldLabel htmlFor={`${id}-show`}>Show the workspace</FieldLabel>
				</FieldContent>
			</Field>
		</div>
	);
}

/** Every practice group, a page at a time, each with the way to its own level and its practices. */
function GroupsTable({
	workspaceSlug,
	overview,
	showWorkspace,
	openGroupSlug,
	onOpenGroup,
}: {
	workspaceSlug: string;
	overview?: PracticesAcrossWorkspace;
	showWorkspace: boolean;
	openGroupSlug?: string;
	onOpenGroup?: (groupSlug: string) => void;
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
			rows={shown}
			scope="group"
			context={overview && splitContextOf(overview)}
			showWorkspace={showWorkspace}
			openKey={openGroupSlug}
			isLoading={overview === undefined}
			more={more}
			noun="practice groups"
			empty={{
				title: "No practice groups here yet",
				description:
					"Once your workspace sets up practice groups, each one appears here with your standing in it.",
			}}
			actions={(row) => (
				<>
					<ProfileLevelLink
						workspaceSlug={workspaceSlug}
						groupSlug={row.key}
						placement="row"
						aria-label={`Open group ${row.name}`}
					>
						Open group
					</ProfileLevelLink>
					<InlineLink
						onClick={() => onOpenGroup?.(row.key)}
						aria-label={`See practices of the group ${row.name}`}
						className="text-xs"
					>
						See practices of the group
					</InlineLink>
				</>
			)}
		/>
	);
}
