import { ArrowRightIcon } from "lucide-react";

import type { WorkspaceGroupSplit } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { NoSuchGroup } from "@/components/practice-profile/practice-profile-blocks";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { DrawerBody } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";

import { OPEN_IN_YOUR_PROFILE, practicesHint, type SplitContext } from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { LevelSplit, SplitLegend } from "./WorkspaceSplitBar";

/** The open group while the page loads, when the page lists no group by its slug, or ready. */
export type WorkspaceGroupLevelState =
	| { status: "loading" }
	| { status: "missing" }
	| { status: "ready"; group: WorkspaceGroupSplit; context: SplitContext };

export interface WorkspaceGroupLevelProps {
	nested?: boolean;
	path: LevelPath;
	state: WorkspaceGroupLevelState;
	/** Goes to the group in the reader's own Practice profile. */
	onGoToProfile: () => void;
	/** Goes to a practice of the group in the reader's own Practice profile. */
	onGoToPractice: (practiceSlug: string) => void;
}

/**
 * One practice group over Practices across the workspace: the group's split beside the title, and
 * each practice of the group beside how the workspace splits across that practice. The reader
 * shows only as the You marker; their own standing, trend and next step are in their Practice
 * profile, which the header's link and every practice's row link go to.
 */
export function WorkspaceGroupLevel({
	nested,
	path,
	state,
	onGoToProfile,
	onGoToPractice,
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
							icon={group.groupIcon}
							color={group.groupColor}
						/>
					)
				}
				// The way on is a link in the header's one line, not a column beside the title. As the
				// header's description it is also the dialog's, so a screen reader reads it on opening.
				description={
					group && (
						<InlineLink
							onClick={onGoToProfile}
							aria-label={`${OPEN_IN_YOUR_PROFILE} ${group.groupName}`}
							className="inline-flex items-center gap-1 self-start font-medium"
						>
							{OPEN_IN_YOUR_PROFILE}
							<ArrowRightIcon className="size-3.5 shrink-0" aria-hidden />
						</InlineLink>
					)
				}
				aside={
					state.status === "missing" ? undefined : (
						<LevelSplit
							state={
								state.status === "ready"
									? { status: "ready", ...state.group, context: state.context }
									: state
							}
						/>
					)
				}
			/>
			<DrawerBody className="flex flex-col gap-4 pt-2">
				{state.status === "missing" ? (
					<NoSuchGroup />
				) : (
					<GroupPractices state={state} onGoToPractice={onGoToPractice} />
				)}
			</DrawerBody>
		</>
	);
}

/** The group's practices, every one of them, as the Practice profile lists a group's practices. */
function GroupPractices({
	state,
	onGoToPractice,
}: {
	state: Exclude<WorkspaceGroupLevelState, { status: "missing" }>;
	onGoToPractice: (practiceSlug: string) => void;
}) {
	const group = state.status === "ready" ? state.group : undefined;
	const rows: ComparisonRow[] = (group?.practices ?? []).map((practice) => ({
		key: practice.practiceSlug,
		name: practice.practiceName,
		subject: <PracticePill name={practice.practiceName} />,
		yourStanding: practice.yourStanding,
		split: practice.split,
	}));
	return (
		<>
			{/* The rule's line, or a line in its place while the level loads, so nothing moves. */}
			{state.status === "loading" && <Skeleton className="h-10 w-full max-w-2xl" />}
			{state.status === "ready" && state.group.practices.length > 0 && (
				<p className="max-w-2xl text-sm text-muted-foreground">
					{practicesHint(state.context.minimumOthers)}
				</p>
			)}
			{/* What the bars show, the page's own legend, above the table it explains. */}
			<SplitLegend />
			<WorkspaceComparisonTable
				aria-label={group === undefined ? "Practices" : `Practices of ${group.groupName}`}
				subjectHead="Practice"
				state={
					state.status === "ready"
						? { status: "ready", rows, context: state.context }
						: { status: "loading" }
				}
				empty={{
					title: "No practices yet",
					description: "Once your workspace reviews a practice in this group, it appears here.",
				}}
				rowLink={(row) => ({
					text: OPEN_IN_YOUR_PROFILE,
					onOpen: () => onGoToPractice(row.key),
				})}
			/>
		</>
	);
}
