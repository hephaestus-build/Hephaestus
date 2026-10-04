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
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell, TableHead, TableRow } from "@/components/ui/table";
import type { RevealedMore } from "@/hooks/use-revealed-rows";

import type { SplitContext } from "./across-workspace-copy";
import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

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

/** The rows while they load, or the rows shown so far with what every split is a part of. */
export type ComparisonTableState =
	| { status: "loading" }
	| {
			status: "ready";
			rows: readonly ComparisonRow[];
			/** The split's reference group and its rule, shared by every row. */
			context: SplitContext;
			/** The end of a list longer than one page; without it the rows are the whole list. */
			more?: RevealedMore;
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
	/** What the list is called at its end: "practice groups", "practices". */
	noun: string;
	empty: { title: string; description: string };
}

/**
 * Practice groups or practices beside how the workspace's developers with a standing split across each,
 * in the practice table frame: the subject, the split with the reader's place on it, and the row's
 * own actions, which the caller decides.
 */
export function WorkspaceComparisonTable({
	"aria-label": label,
	subjectHead,
	state,
	rowLink,
	openKey,
	noun,
	empty,
}: WorkspaceComparisonTableProps) {
	const ready = state.status === "ready" ? state : undefined;
	const columns = 3;
	return (
		<PracticeTableFrame
			aria-label={label}
			columns={columns}
			head={
				<>
					<TableHead className="w-96">{subjectHead}</TableHead>
					<TableHead>Developers in this workspace</TableHead>
					<TableHead className="w-28">
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
						<Skeleton className="h-5 w-48" />
					</TableCell>
					<TableCell>
						<Skeleton className="h-9 w-full" />
					</TableCell>
					<TableCell>
						<Skeleton className="ml-auto h-5 w-32" />
					</TableCell>
				</>
			}
			end={
				ready?.more?.hasMore === true && (
					<TableRow variant="static">
						<TableCell colSpan={columns} className="p-3">
							<Button variant="outline" size="sm" onClick={ready.more.onShowMore}>
								Show more {noun}
							</Button>
						</TableCell>
					</TableRow>
				)
			}
		/>
	);
}
