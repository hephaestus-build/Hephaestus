import type { WorkspaceGroupSplit } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { NoSuchGroup } from "@/components/practice-profile/practice-profile-blocks";
import { getGroupVisual, groupMark } from "@/components/practice-vocabulary/group-visuals";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { StandingBasisNote } from "@/components/practice-vocabulary/StandingBasisNote";
import { DrawerBody } from "@/components/ui/drawer";

import { GROUPS_LOAD_ERROR, OPEN_IN_YOUR_PROFILE } from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { LevelSplit, SplitLegend } from "./WorkspaceSplitBar";

/** `missing` when the page lists no group by the slug in the address. */
export type WorkspaceGroupLevelState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "missing" }
	| { status: "ready"; group: WorkspaceGroupSplit };

export interface WorkspaceGroupLevelProps {
	nested?: boolean;
	path: LevelPath;
	state: WorkspaceGroupLevelState;
	onGoToPractice: (practiceSlug: string) => void;
	/** The practice the reader came from in their profile, marked among the group's practices. */
	focusPracticeSlug?: string;
}

/**
 * One practice group over Practices across the workspace: the group's split beside the title, then
 * each practice's split. The reader shows only as the You marker. Their own standing, trend and
 * next step stay in their Practice profile, which every row link and the level's footer open.
 */
export function WorkspaceGroupLevel({
	nested,
	path,
	state,
	onGoToPractice,
	focusPracticeSlug,
}: WorkspaceGroupLevelProps) {
	const group = state.status === "ready" ? state.group : undefined;
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current="Group"
				title={group?.groupName ?? "Practice group"}
				loading={state.status === "loading"}
				mark={
					group && (
						<GroupPill
							size="lg"
							slug={group.groupSlug}
							name={group.groupName}
							icon={group.groupIcon ?? undefined}
							color={group.groupColor ?? undefined}
						/>
					)
				}
				chips={group && <StandingBasisNote basis={group.yourBasis} />}
				aside={
					state.status === "loading" || state.status === "ready" ? (
						<LevelSplit
							state={
								state.status === "ready"
									? {
											status: "ready",
											split: state.group.split,
											yourStanding: state.group.yourStanding ?? undefined,
										}
									: state
							}
						/>
					) : undefined
				}
			/>
			<DrawerBody
				className="flex flex-col gap-4 pt-2"
				aria-busy={state.status === "loading" || undefined}
			>
				{state.status === "error" && (
					<QueryErrorAlert error={state.error} title={GROUPS_LOAD_ERROR} onRetry={state.onRetry} />
				)}
				{state.status === "missing" && <NoSuchGroup />}
				{(state.status === "loading" || state.status === "ready") && (
					<GroupPractices
						state={state}
						onGoToPractice={onGoToPractice}
						focusPracticeSlug={focusPracticeSlug}
					/>
				)}
			</DrawerBody>
		</>
	);
}

function GroupPractices({
	state,
	onGoToPractice,
	focusPracticeSlug,
}: {
	state: Extract<WorkspaceGroupLevelState, { status: "loading" | "ready" }>;
	onGoToPractice: (practiceSlug: string) => void;
	focusPracticeSlug?: string;
}) {
	const group = state.status === "ready" ? state.group : undefined;
	// Each practice row carries the group's icon in its color, so a row reads as part of this group.
	const rowMark = group && groupMark(getGroupVisual(group.groupIcon, group.groupColor));
	const rows: ComparisonRow[] = (group?.practices ?? []).map((practice) => ({
		key: practice.practiceSlug,
		name: practice.practiceName,
		subject: <PracticePill name={practice.practiceName} group={rowMark} />,
		yourStanding: practice.yourStanding ?? undefined,
		yourBasis: practice.yourBasis ?? undefined,
		split: practice.split,
	}));
	return (
		<>
			<SplitLegend scope="practice" />
			<WorkspaceComparisonTable
				aria-label={group === undefined ? "Practices" : `Practices of ${group.groupName}`}
				subjectHead="Practice"
				state={state.status === "ready" ? { status: "ready", rows } : { status: "loading" }}
				empty={{
					title: "No practices yet",
					description: "Once your workspace reviews a practice in this group, it appears here.",
				}}
				rowLink={(row) => ({
					text: OPEN_IN_YOUR_PROFILE,
					onOpen: () => onGoToPractice(row.key),
				})}
				markedKey={focusPracticeSlug}
			/>
		</>
	);
}
