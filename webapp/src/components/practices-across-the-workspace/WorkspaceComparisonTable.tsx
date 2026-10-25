import { ClipboardCheckIcon } from "lucide-react";
import { type ReactNode, useId } from "react";

import type { WorkspaceSplit } from "@/api/types.gen";
import type { PracticeGroupStandingValue } from "@/components/practice-vocabulary/practice-group-standing-defs";
import {
	OPEN_ROW_BAR,
	PracticeTableFrame,
	PracticeTableRow,
	STACKED,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";
import type { StandingBasis } from "@/components/practice-vocabulary/standing-basis-defs";
import { StandingBasisNote } from "@/components/practice-vocabulary/StandingBasisNote";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell, TableHead } from "@/components/ui/table";

import { NOBODY_YET } from "./across-workspace-copy";
import { EmptyTrack, WorkspaceSplitBar, WorkspaceSplitBarSkeleton } from "./WorkspaceSplitBar";

/** The hidden words of the marked row, read after its name. */
export const CAME_FROM_YOUR_PROFILE = ", where you came from in your Practice profile";

/** One practice group or one practice. */
export interface ComparisonRow {
	key: string;
	/** Ends the accessible name of the row's link, so each row's link is unique on the screen. */
	name: string;
	/** The name as the subject cell draws it: a group's pill, a practice's pill. */
	subject: ReactNode;
	/** The part the You bracket is on; absent where the split marks no one. */
	yourStanding?: PracticeGroupStandingValue;
	/** What the reader's own standing was read from, said under the subject when it is earlier reviews. */
	yourBasis?: StandingBasis;
	split: WorkspaceSplit;
}

export type ComparisonTableState =
	| { status: "loading" }
	| { status: "ready"; rows: readonly ComparisonRow[] };

export interface WorkspaceComparisonTableProps {
	"aria-label": string;
	/** "Practice group", "Practice". */
	subjectHead: string;
	state: ComparisonTableState;
	/** The link at the row's end, which a press anywhere on the row also follows. */
	rowLink: (row: ComparisonRow) => { text: string; onOpen: () => void };
	/** The row whose level is open over the page. */
	openKey?: string;
	/**
	 * The row the reader came from in their Practice profile. It wears the same bar as an open
	 * row, but says so in words too: `aria-current` and a hidden sentence, since no level is open.
	 */
	markedKey?: string;
	empty: { title: string; description: string };
}

/**
 * Practice groups or practices, each beside its split, in the practice table frame. Every row is
 * listed with no paging, as the Practice profile lists them. When no split counts anybody yet, the
 * reason shows once above the table, not once per row.
 */
export function WorkspaceComparisonTable({
	"aria-label": label,
	subjectHead,
	state,
	rowLink,
	openKey,
	markedKey,
	empty,
}: WorkspaceComparisonTableProps) {
	const rows = state.status === "ready" ? state.rows : [];
	const nobodyYet = rows.length > 0 && rows.every((row) => row.split.developers === 0);
	// Each cell then holds only a hidden track, so the table is described by the one reason.
	const reasonId = useId();
	return (
		<>
			{nobodyYet && (
				<p id={reasonId} className="text-sm text-muted-foreground">
					{NOBODY_YET}.
				</p>
			)}
			<PracticeTableFrame
				aria-label={label}
				aria-describedby={nobodyYet ? reasonId : undefined}
				columns={3}
				// On a phone a row stacks as PracticeTable's do, so the bar never scrolls out of sight.
				tableClassName={STACKED}
				head={
					<>
						<TableHead className="w-96">{subjectHead}</TableHead>
						<TableHead>Developers in this workspace</TableHead>
						<TableHead className="w-32">
							<span className="sr-only">Open</span>
						</TableHead>
					</>
				}
				rows={rows}
				rowKey={(row) => row.key}
				renderRow={(row) => (
					<PracticeTableRow
						open={row.key === openKey}
						marked={row.key === markedKey}
						link={{ ...rowLink(row), name: row.name }}
					>
						<SubjectCell
							badge={
								row.key === markedKey ? (
									<>
										{row.subject}
										<span className="sr-only">{CAME_FROM_YOUR_PROFILE}</span>
									</>
								) : (
									row.subject
								)
							}
							className={OPEN_ROW_BAR}
						>
							<StandingBasisNote basis={row.yourBasis} />
						</SubjectCell>
						<TableCell className="align-top whitespace-normal @max-[38rem]:basis-full">
							{nobodyYet ? (
								<EmptyTrack />
							) : (
								<WorkspaceSplitBar split={row.split} yourStanding={row.yourStanding} />
							)}
						</TableCell>
					</PracticeTableRow>
				)}
				empty={{ icon: <ClipboardCheckIcon />, ...empty }}
				isLoading={state.status === "loading"}
				loadingRow={
					<>
						<TableCell>
							<span className="flex items-center gap-2">
								<Skeleton className="size-4 shrink-0 rounded-full" />
								<Skeleton className="h-4 w-48" />
							</span>
						</TableCell>
						<TableCell className="align-top whitespace-normal @max-[38rem]:basis-full">
							<WorkspaceSplitBarSkeleton />
						</TableCell>
						<TableCell>
							<Skeleton className="ml-auto h-4 w-24" />
						</TableCell>
					</>
				}
			/>
		</>
	);
}
