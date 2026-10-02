import { ClipboardCheckIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { WorkspaceSplit } from "@/api/types.gen";
import { statusToneClass } from "@/components/common/status-def";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";
import { OPEN_ROW_BAR, PracticeTableFrame } from "@/components/practice-vocabulary/PracticeTable";
import { StandingBadge } from "@/components/practice-vocabulary/StandingBadge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { TableCell, TableHead, TableRow } from "@/components/ui/table";
import { useInView } from "@/hooks/use-in-view";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

import { SPLIT_STANDINGS, type SplitContext } from "./across-workspace-copy";
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

export interface WorkspaceComparisonTableProps {
	"aria-label": string;
	/** "Practice group", "Practice". */
	subjectHead: string;
	/** The rows loaded so far; `more` says whether the list goes on. */
	rows: readonly ComparisonRow[];
	/** Whose standing the table shows: a badge's sentence is worded for a group or a practice. */
	scope: "group" | "practice";
	/** The split's reference group and its rule, shared by every row; absent until the rows are in. */
	context?: SplitContext;
	/** Off, the table shows the reader's own standing and no split. */
	showWorkspace: boolean;
	/** The row's own actions, at its end: what a level opens or where the reader goes from here. */
	actions: (row: ComparisonRow) => ReactNode;
	/** The row whose level is open over the page, which keeps a bar on its leading edge. */
	openKey?: string;
	isLoading?: boolean;
	/** The end of a list that loads more as it is read; without it the rows are the whole list. */
	more?: MorePages;
	/** What the list is called at its end: "practice groups", "practices". */
	noun: string;
	empty: { title: string; description: string };
}

const COLUMNS = 3;

/**
 * Practice groups or practices beside how the workspace's observed developers split across each,
 * in the practice table frame: the subject, the split with the reader's place on it, and the row's
 * own actions, which the caller decides. The head of the split column is its key.
 */
export function WorkspaceComparisonTable({
	"aria-label": label,
	subjectHead,
	rows,
	scope,
	context,
	showWorkspace,
	actions,
	openKey,
	isLoading = false,
	more,
	noun,
	empty,
}: WorkspaceComparisonTableProps) {
	return (
		<PracticeTableFrame
			aria-label={label}
			columns={COLUMNS}
			head={
				<>
					<TableHead className="w-72">{subjectHead}</TableHead>
					<TableHead>{showWorkspace ? <SplitKey /> : "Your standing"}</TableHead>
					<TableHead className="w-56">
						<span className="sr-only">Actions</span>
					</TableHead>
				</>
			}
			rows={rows}
			rowKey={(row) => row.key}
			renderRow={(row) => (
				<TableRow
					variant="static"
					data-state={row.key === openKey ? "open" : undefined}
					className="group/row"
				>
					<TableCell className={cn(OPEN_ROW_BAR, "align-top whitespace-normal")}>
						{row.subject}
					</TableCell>
					<TableCell className="align-top whitespace-normal">
						{showWorkspace && context !== undefined ? (
							<WorkspaceSplitBar split={row.split} yourStanding={row.yourStanding} {...context} />
						) : (
							<StandingBadge standing={row.yourStanding} scope={scope} />
						)}
					</TableCell>
					<TableCell className="align-top whitespace-normal">
						<div className="flex flex-col items-start gap-2">{actions(row)}</div>
					</TableCell>
				</TableRow>
			)}
			empty={{ icon: <ClipboardCheckIcon />, ...empty }}
			isLoading={isLoading}
			loadingRow={
				<>
					<TableCell>
						<Skeleton className="h-5 w-48" />
					</TableCell>
					<TableCell>
						<Skeleton className="h-9 w-full" />
					</TableCell>
					<TableCell>
						<Skeleton className="h-6 w-24" />
					</TableCell>
				</>
			}
			end={more && <ListEndRow {...more} noun={noun} />}
		/>
	);
}

/** The split column's head: the three parts in the bar's order, each with its icon. */
function SplitKey() {
	return (
		<span className="flex flex-wrap items-center gap-x-3 gap-y-1 font-normal text-muted-foreground">
			<span className="sr-only">Developers in this workspace:</span>
			{SPLIT_STANDINGS.map((standing) => {
				const def = PRACTICE_GROUP_STANDING_DEFS[standing];
				const Icon = def.icon;
				return (
					<span key={standing} className="inline-flex items-center gap-1 text-xs">
						<Icon
							className={cn("size-3 shrink-0", statusToneClass(def.badgeVariant))}
							aria-hidden
						/>
						{def.label}
					</span>
				);
			})}
		</span>
	);
}

/**
 * The end of the list, where the rows after these are asked for. A reader who has read to the
 * bottom has said what a press would have said, so the list loads itself as this row comes into
 * view. A load that failed is the one case that asks, with the press offered back.
 */
function ListEndRow({
	hasMore,
	isLoadingMore,
	loadMoreError,
	onLoadMore,
	noun,
}: MorePages & { noun: string }) {
	const failed = loadMoreError !== undefined;
	// Off while a page is in flight and once a load has failed, so the sentinel cannot ask twice for
	// one page nor spin on an endpoint that answers with an error.
	const sentinel = useInView(onLoadMore, hasMore && !isLoadingMore && !failed);
	if (!hasMore && !failed) {
		return null;
	}
	return (
		<TableRow variant="static" ref={sentinel}>
			<TableCell colSpan={COLUMNS} className="p-3 whitespace-normal">
				{failed ? (
					<span className="flex flex-wrap items-center gap-2 text-sm">
						<span className="text-muted-foreground">Could not load more {noun}.</span>
						<Button type="button" variant="outline" size="xs" onClick={onLoadMore}>
							Show more {noun}
						</Button>
					</span>
				) : (
					<span aria-live="polite" className="text-sm text-muted-foreground">
						{isLoadingMore ? `Loading more ${noun}…` : ""}
					</span>
				)}
			</TableCell>
		</TableRow>
	);
}
