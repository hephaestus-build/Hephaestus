import { Link } from "@tanstack/react-router";
import { FileSearchIcon } from "lucide-react";

import type { ScanReportItem } from "@/api/types.gen";
import { resultLevel } from "@/components/admin/practice-reviews/review-levels";
import { EmptyState } from "@/components/common/EmptyState";
import { type FilterOption, FilterToggle } from "@/components/common/FilterToggle";
import { LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { OUTCOME_DEFS, problemCount } from "@/components/practice-vocabulary/outcome-defs";
import { PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import {
	REMOVED_FILE_DEF,
	SCAN_ENTRY_DEFS,
	type ScanEntryStatus,
} from "@/components/practice-vocabulary/scan-entry-defs";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { formatDate } from "@/lib/dates";
import { itemName } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

import { notReviewedSentence, type ScanProgress, withheldSentence } from "./scan-display";
import { ScanListMore, type ScanListState } from "./scan-list-state";
import { SCAN_FILE_FILTERS } from "./scans-search";

type FileFilter = ScanEntryStatus | "ALL";

const FILTER_OPTIONS: readonly FilterOption<FileFilter>[] = [
	{ value: "ALL", label: "All" },
	...SCAN_FILE_FILTERS.map((status) => ({ value: status, label: SCAN_ENTRY_DEFS[status].label })),
];

export interface ScanItemTableProps {
	/** Each row with a result opens it in Practice reviews in this workspace. */
	workspaceSlug: string;
	state: ScanListState<ScanReportItem>;
	filter: ScanEntryStatus | undefined;
	onFilterChange: (filter: ScanEntryStatus | undefined) => void;
	progress: ScanProgress;
}

const SKELETON_COLUMNS = ["w-56", "w-40", "w-20", "w-64", null] as const;

const NO_FILES: Record<ScanProgress, { title: string; description?: string }> = {
	active: { title: "No files listed yet" },
	complete: { title: "This scan listed no files" },
	stopped: {
		title: "No files were listed",
		description: "The scan stopped before it could list the repository’s files.",
	},
};

// Earlier evidence on a not-reviewed row is not this scan's result.
export function ScanItemTable({
	workspaceSlug,
	state,
	filter,
	onFilterChange,
	progress,
}: ScanItemTableProps) {
	return (
		<section aria-label="Files" className="space-y-4">
			<FilterToggle
				label="File status"
				options={FILTER_OPTIONS}
				value={filter ?? "ALL"}
				onChange={(next) => onFilterChange(next === "ALL" ? undefined : next)}
			/>
			<ScanItemResults
				workspaceSlug={workspaceSlug}
				state={state}
				filtered={filter !== undefined}
				progress={progress}
			/>
		</section>
	);
}

function ScanItemResults({
	workspaceSlug,
	state,
	filtered,
	progress,
}: {
	workspaceSlug: string;
	state: ScanListState<ScanReportItem>;
	filtered: boolean;
	progress: ScanProgress;
}) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the files"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "ready" && state.entries.length === 0) {
		if (filtered) {
			return (
				<EmptyState icon={<FileSearchIcon />} headingLevel={2} title="No files match this filter" />
			);
		}
		return <EmptyState icon={<FileSearchIcon />} headingLevel={2} {...NO_FILES[progress]} />;
	}
	return (
		<div className="space-y-4">
			<Table
				bordered
				aria-label="Files and practices"
				aria-busy={state.status === "loading" || state.loadingMore || undefined}
			>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">File</TableHead>
						<TableHead scope="col">Practice</TableHead>
						<TableHead scope="col">Status</TableHead>
						<TableHead scope="col">Observation</TableHead>
						<TableHead scope="col" className="w-32">
							<span className="sr-only">Open</span>
						</TableHead>
					</TableRow>
				</TableHeader>
				{state.status === "loading" ? (
					<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={5} />
				) : (
					<TableBody>
						{state.entries.map((entry) => {
							const path = entry.item.path ?? itemName(entry.item);
							const resultId = resultIdOf(entry);
							return (
								<PracticeTableRow
									key={`${entry.item.id}:${entry.practiceSlug}`}
									link={
										resultId === undefined
											? undefined
											: {
													text: "Open observation",
													name: `${path}, ${entry.practiceName}`,
													render: (
														<Link
															to="/w/$workspaceSlug/admin/practices/reviews/results"
															params={{ workspaceSlug }}
															search={detailSearch(resultLevel(resultId))}
														/>
													),
												}
									}
								>
									<TableCell className="max-w-xs">
										<span className="block truncate font-mono text-xs">{path}</span>
										{entry.item.removedAt !== null && (
											<StatusBadge def={REMOVED_FILE_DEF} className="mt-1" />
										)}
									</TableCell>
									<TableCell className="max-w-48 text-sm">
										<span className="block truncate">{entry.practiceName}</span>
									</TableCell>
									<TableCell>
										<StatusBadge def={SCAN_ENTRY_DEFS[entry.status]} />
									</TableCell>
									<TableCell className="text-sm">
										<EntryResult entry={entry} />
									</TableCell>
								</PracticeTableRow>
							);
						})}
					</TableBody>
				)}
				{state.status === "ready" && state.loadingMore && (
					<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={LOADING_MORE_ROWS} continues />
				)}
			</Table>
			{state.status === "ready" && (
				<ScanListMore
					hasNext={state.hasNext}
					loadingMore={state.loadingMore}
					loadMoreError={state.loadMoreError}
					onLoadMore={state.onLoadMore}
					noun="files"
				/>
			)}
		</div>
	);
}

/** This scan's own result; earlier evidence on a row that was not reviewed is not one. */
function resultIdOf(entry: ScanReportItem): string | undefined {
	return entry.status === "JUDGED" || entry.status === "REUSED" ? entry.resultId : undefined;
}

function EntryResult({ entry }: { entry: ScanReportItem }) {
	switch (entry.status) {
		case "JUDGED":
		case "REUSED": {
			return (
				<div className="space-y-1">
					<div className="flex flex-wrap items-center gap-2">
						<StatusBadge def={OUTCOME_DEFS[entry.outcome]} />
						{entry.problemCount > 0 && (
							<span className="text-muted-foreground">{problemCount(entry.problemCount)}</span>
						)}
					</div>
					{hasText(entry.summary) && <p className="line-clamp-2">{entry.summary}</p>}
					{entry.status === "REUSED" && (
						<p className="text-xs text-muted-foreground">
							Reused from a review on {formatDate(entry.recordedAt)}.
						</p>
					)}
				</div>
			);
		}
		case "WITHHELD": {
			return <p className="text-muted-foreground">{withheldSentence(entry.reason)}</p>;
		}
		case "NOT_REVIEWED": {
			return (
				<div className="space-y-1">
					<p className="text-muted-foreground">{notReviewedSentence(entry.reason)}</p>
					<EarlierEvidence result={entry.lastResult} />
				</div>
			);
		}
		case "LISTED": {
			return (
				<div className="space-y-1">
					<p className="text-muted-foreground">Not reviewed yet.</p>
					<EarlierEvidence result={entry.lastResult} />
				</div>
			);
		}
	}
}

function EarlierEvidence({
	result,
}: {
	result: Extract<ScanReportItem, { status: "LISTED" }>["lastResult"];
}) {
	if (result === null) {
		return null;
	}
	return (
		<p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
			<span>Earlier evidence, not from this scan: {formatDate(result.recordedAt)}</span>
			<StatusBadge def={OUTCOME_DEFS[result.outcome]} />
		</p>
	);
}
