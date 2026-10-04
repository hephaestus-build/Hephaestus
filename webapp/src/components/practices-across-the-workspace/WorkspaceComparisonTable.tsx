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

import type { SplitContext } from "./across-workspace-copy";
import { WorkspaceSplitBar, WorkspaceSplitBarSkeleton } from "./WorkspaceSplitBar";

/** One practice group or one practice, as the table compares it. */
export interface ComparisonRow {
	key: string;
	/** What the row is, by name: the accessible names of its actions end with it. */
	name: string;
	/** The name as the subject cell draws it: a group's icon and colour, a practice's words. */
	subject: ReactNode;
	yourStanding: PracticeGroupStandingValue;
	split: WorkspaceSplit;
}

/** The rows while they load, or every row with what every split is a part of. */
export type ComparisonTableState =
	| { status: "loading" }
	| {
			status: "ready";
			rows: readonly ComparisonRow[];
			/** The split's reference group and its rule, shared by every row. */
			context: SplitContext;
	  };

export interface WorkspaceComparisonTableProps {
	"aria-label": string;
	/** "Practice group", "Practice". */
	subjectHead: string;
	state: ComparisonTableState;
	/**
	 * The row's link at its end, drawn as the reviews table draws "Open review", which a press
	 * anywhere on the row also follows.
	 */
	rowLink: (row: ComparisonRow) => Omit<PracticeTableRowLink, "name">;
	/** The row whose level is open over the page, which keeps a bar on its leading edge. */
	openKey?: string;
	empty: { title: string; description: string };
}

/**
 * Practice groups or practices beside how the workspace's developers with a standing split across each,
 * in the practice table frame: the subject, the split with the reader's place on it, and the row's
 * own actions, which the caller decides. Every row is listed, as the Practice profile lists them.
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
								{...ready.context}
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
					{/* The row's link is drawn once its row is in, as the Practice profile leaves it. */}
					<TableCell />
				</>
			}
		/>
	);
}
