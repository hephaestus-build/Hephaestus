import { ArrowRightIcon } from "lucide-react";

import type { WorkspaceGroupSplit } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { NoSuchGroup } from "@/components/practice-profile/practice-profile-blocks";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { DrawerBody } from "@/components/ui/drawer";
import { useRevealedRows } from "@/hooks/use-revealed-rows";

import { practicesHint, type SplitContext } from "./across-workspace-copy";
import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";
import { SplitLegend, WorkspaceSplitBar } from "./WorkspaceSplitBar";

/** How many practices the level lists before it offers more. */
export const PRACTICES_PAGE_SIZE = 20;

/** The open group while the page loads, when the page lists no group by its slug, or ready. */
export type WorkspaceGroupLevelState =
	| { status: "loading" }
	| { status: "missing" }
	| { status: "ready"; group: WorkspaceGroupSplit; context: SplitContext };

export interface WorkspaceGroupLevelProps {
	nested?: boolean;
	path: LevelPath;
	state: WorkspaceGroupLevelState;
	/** Opens a practice of the group as the next level, the reader's own practice. */
	onOpenPractice: (practiceSlug: string) => void;
	/** Opens the reader's own group as the next level: their standing, trend and practices in it. */
	onOpenOwnGroup: () => void;
}

/**
 * One practice group over Practices across the workspace: the reader's own standing and trend in
 * it with the way to their own group under them, the group's split beside the title, and each
 * practice of the group beside how the workspace splits across that practice. Both open the next
 * level over this one.
 */
export function WorkspaceGroupLevel({
	nested,
	path,
	state,
	onOpenPractice,
	onOpenOwnGroup,
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
				// Side by side, as the profile's own group level shows them.
				chips={
					group && (
						<>
							<StandingBadge standing={group.yourStanding} scope="group" />
							<TrendNote
								direction={group.yourDirection}
								support={group.yourTrendSupport}
								scope="group"
							/>
						</>
					)
				}
				// The way on is a link in the header's one line, not a column beside the title. As the
				// header's description it is also the dialog's, so a screen reader reads it on opening.
				description={
					group && (
						<InlineLink
							onClick={onOpenOwnGroup}
							aria-label={`Open your group ${group.groupName}`}
							className="inline-flex items-center gap-1 self-start font-medium"
						>
							Open your group
							<ArrowRightIcon className="size-3.5 shrink-0" aria-hidden />
						</InlineLink>
					)
				}
				aside={
					state.status === "ready" ? (
						<div className="w-full sm:w-88">
							<WorkspaceSplitBar
								split={state.group.split}
								yourStanding={state.group.yourStanding}
								showYourWord={false}
								{...state.context}
							/>
						</div>
					) : undefined
				}
			/>
			<DrawerBody className="flex flex-col gap-4 pt-2">
				{state.status === "missing" ? (
					<NoSuchGroup />
				) : (
					<GroupPractices key={group?.groupSlug} state={state} onOpenPractice={onOpenPractice} />
				)}
			</DrawerBody>
		</>
	);
}

/** The group's practices, keyed on the group so a new group starts from its first page. */
function GroupPractices({
	state,
	onOpenPractice,
}: {
	state: Exclude<WorkspaceGroupLevelState, { status: "missing" }>;
	onOpenPractice: (practiceSlug: string) => void;
}) {
	const group = state.status === "ready" ? state.group : undefined;
	const rows: ComparisonRow[] = (group?.practices ?? []).map((practice) => ({
		key: practice.practiceSlug,
		name: practice.practiceName,
		subject: <PracticePill name={practice.practiceName} />,
		yourStanding: practice.yourStanding,
		split: practice.split,
	}));
	const { shown, ...more } = useRevealedRows(rows, PRACTICES_PAGE_SIZE);
	return (
		<>
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
						? { status: "ready", rows: shown, context: state.context, more }
						: { status: "loading" }
				}
				noun="practices"
				empty={{
					title: "No practices yet",
					description: "Once your workspace reviews a practice in this group, it appears here.",
				}}
				rowLink={(row) => ({
					text: "Open practice",
					onOpen: () => onOpenPractice(row.key),
				})}
			/>
		</>
	);
}
