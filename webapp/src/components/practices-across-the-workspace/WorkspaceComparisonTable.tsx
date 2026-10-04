import { ClipboardCheckIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { WorkspaceSplit } from "@/api/types.gen";
import type { PracticeGroupStandingValue } from "@/components/practice-vocabulary/practice-group-standing-defs";
import {
	OPEN_ROW_BAR,
	PracticeTableFrame,
	PracticeTableRow,
	type PracticeTableRowLink,
	SubjectCell,
} from "@/components/practice-vocabulary/PracticeTable";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell, TableHead } from "@/components/ui/table";

import { WorkspaceSplitBar, WorkspaceSplitBarSkeleton } from "./WorkspaceSplitBar";

/** One practice group or one practice. */
export interface ComparisonRow {
	key: string;
	/** Ends the accessible name of the row's link, so each row's link is unique on the screen. */
	name: string;
	/** The name as the subject cell draws it: a group's pill, a practice's pill. */
	subject: ReactNode;
	/** The part the You marker is on; absent where the split marks no one. */
	yourStanding?: PracticeGroupStandingValue;
	split: WorkspaceSplit;
}

export type ComparisonTableState =
	| { status: "loading" }
	| { status: "ready"; rows: readonly ComparisonRow[]; readerCounted: boolean };

export interface WorkspaceComparisonTableProps {
	"aria-label": string;
	/** "Practice group", "Practice". */
	subjectHead: string;
	state: ComparisonTableState;
	/** The link at the row's end, which a press anywhere on the row also follows. */
	rowLink: (row: ComparisonRow) => Omit<PracticeTableRowLink, "name">;
	/** The row whose level is open over the page. */
	openKey?: string;
	empty: { title: string; description: string };
}

/**
 * Practice groups or practices, each beside its split, in the practice table frame. Every row is
 * listed with no paging, as the Practice profile lists them.
 */
export function WorkspaceComparisonTable({
	"aria-label": label,
	subjectHead,
	state,
	rowLink,
	openKey,
	empty,
}: WorkspaceComparisonTableProps) {
	const ready = state.status === "ready" ? state : undefined;
	return (
		<PracticeTableFrame
			aria-label={label}
			columns={3}
			head={
				<>
					<TableHead className="w-96">{subjectHead}</TableHead>
					<TableHead>Developers in this workspace</TableHead>
					<TableHead className="w-32">
						<span className="sr-only">Open</span>
					</TableHead>
				</>
			}
			rows={ready?.rows ?? []}
			rowKey={(row) => row.key}
			renderRow={(row) => (
				<PracticeTableRow open={row.key === openKey} link={{ ...rowLink(row), name: row.name }}>
					<SubjectCell badge={row.subject} className={OPEN_ROW_BAR} />
					<TableCell className="align-top whitespace-normal">
						{ready !== undefined && (
							<WorkspaceSplitBar
								split={row.split}
								yourStanding={row.yourStanding}
								readerCounted={ready.readerCounted}
							/>
						)}
					</TableCell>
				</PracticeTableRow>
			)}
			empty={{ icon: <ClipboardCheckIcon />, ...empty }}
			isLoading={state.status === "loading"}
			loadingRow={
				<>
					<TableCell>
						<Skeleton className="h-5 w-48 rounded-full" />
					</TableCell>
					<TableCell className="align-top">
						<WorkspaceSplitBarSkeleton />
					</TableCell>
					<TableCell />
				</>
			}
		/>
	);
}
