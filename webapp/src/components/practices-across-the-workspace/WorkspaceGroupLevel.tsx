import { ArrowRightIcon } from "lucide-react";

import type { PracticesAcrossWorkspace, WorkspaceGroupSplit } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { NoSuchGroup } from "@/components/practice-profile/practice-profile-blocks";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { DrawerBody } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";

import { barsHint, OPEN_IN_YOUR_PROFILE } from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { LevelSplit, SplitLegend } from "./WorkspaceSplitBar";

/** `missing` when the page lists no group by the slug in the address. */
export type WorkspaceGroupLevelState =
	| { status: "loading" }
	| { status: "missing" }
	| ({ status: "ready"; group: WorkspaceGroupSplit } & Pick<
			PracticesAcrossWorkspace,
			"readerCounted" | "minimumOthers"
	  >);

export interface WorkspaceGroupLevelProps {
	nested?: boolean;
	path: LevelPath;
	state: WorkspaceGroupLevelState;
	onGoToProfile: () => void;
	onGoToPractice: (practiceSlug: string) => void;
}

/**
 * One practice group over Practices across the workspace: the group's split beside the title, then
 * each practice's split. The reader shows only as the You marker. Their own standing, trend and
 * next step stay in their Practice profile, which the header link and every row link open.
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
				// As the header's description it is also the dialog's, so a screen reader reads it on opening.
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
									? {
											status: "ready",
											split: state.group.split,
											yourStanding: state.group.yourStanding,
											readerCounted: state.readerCounted,
										}
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
			{/* The skeleton holds the hint's place, so nothing moves when it arrives. */}
			{state.status === "loading" && <Skeleton className="h-10 w-full max-w-2xl" />}
			{state.status === "ready" && state.group.practices.length > 0 && (
				<p className="max-w-2xl text-sm text-muted-foreground">
					{barsHint(state.minimumOthers, "practice")}
				</p>
			)}
			<SplitLegend />
			<WorkspaceComparisonTable
				aria-label={group === undefined ? "Practices" : `Practices of ${group.groupName}`}
				subjectHead="Practice"
				state={
					state.status === "ready"
						? { status: "ready", rows, readerCounted: state.readerCounted }
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
