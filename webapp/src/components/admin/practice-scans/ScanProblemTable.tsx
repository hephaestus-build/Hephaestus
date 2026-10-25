import { Link } from "@tanstack/react-router";
import { CircleCheckIcon, CircleDashedIcon, HourglassIcon, SearchXIcon } from "lucide-react";

import type { ProblemChanges, ScanReportProblem } from "@/api/types.gen";
import { citationLocation } from "@/components/admin/practice-reviews/ResultEvidence";
import { problemLevel } from "@/components/admin/practice-reviews/review-levels";
import { EmptyState } from "@/components/common/EmptyState";
import { type FilterOption, FilterToggle } from "@/components/common/FilterToggle";
import { LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import {
	baselineDef,
	PROBLEM_BASELINE_STATES,
	type ProblemBaselineState,
	SCAN_BASELINE_DEFS,
} from "@/components/practice-vocabulary/scan-baseline-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { itemName } from "@/lib/item-kinds";

import type { ScanProgress } from "./scan-display";
import { ScanListMore, type ScanListState } from "./scan-list-state";

type BaselineFilter = ProblemBaselineState | "ALL";

export interface ScanProblemTableProps {
	/** Each row opens its problem in Practice reviews in this workspace. */
	workspaceSlug: string;
	state: ScanListState<ScanReportProblem>;
	filter: ProblemBaselineState | undefined;
	onFilterChange: (filter: ProblemBaselineState | undefined) => void;
	changes: ProblemChanges;
	pairsWithoutResult: number;
	/** Only a scan through every file can say it recorded no problems. */
	progress: ScanProgress;
}

const SKELETON_COLUMNS = ["w-64", "w-48", "w-32", "w-20", null] as const;

function changeCount(changes: ProblemChanges, state: ProblemBaselineState): number {
	switch (state) {
		case "NEW": {
			return changes.new ?? 0;
		}
		case "UNCHANGED": {
			return changes.unchanged;
		}
		case "UPDATED": {
			return changes.updated;
		}
		case "ABSENT": {
			return changes.absent;
		}
	}
}

// A code citation without a path of its own stands in the scanned file; any other citation's
// coordinates point into captured input, so the row names the scanned file without lines.
function citationText(entry: ScanReportProblem): string {
	const citation = entry.problem.citations[0];
	const located =
		citation === undefined
			? undefined
			: citationLocation({ ...citation, path: citation.path ?? entry.item.path });
	return located ?? entry.item.path ?? itemName(entry.item);
}

// Absence proves a fix only after another review under the same standard and method.
export function ScanProblemTable({
	workspaceSlug,
	state,
	filter,
	onFilterChange,
	changes,
	pairsWithoutResult,
	progress,
}: ScanProblemTableProps) {
	const options: readonly FilterOption<BaselineFilter>[] = [
		{ value: "ALL", label: "All" },
		...PROBLEM_BASELINE_STATES.map((value) => ({
			value,
			label: `${SCAN_BASELINE_DEFS[value].label} (${changeCount(changes, value)})`,
		})),
	];
	return (
		<section aria-label="Problems" className="space-y-4">
			<FilterToggle
				label="Compared with the earlier scan"
				options={options}
				value={filter ?? "ALL"}
				onChange={(next) => onFilterChange(next === "ALL" ? undefined : next)}
			/>
			<ScanProblemResults
				workspaceSlug={workspaceSlug}
				state={state}
				filtered={filter !== undefined}
				pairsWithoutResult={pairsWithoutResult}
				progress={progress}
			/>
		</section>
	);
}

function ScanProblemResults({
	workspaceSlug,
	state,
	filtered,
	pairsWithoutResult,
	progress,
}: {
	workspaceSlug: string;
	state: ScanListState<ScanReportProblem>;
	filtered: boolean;
	pairsWithoutResult: number;
	progress: ScanProgress;
}) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the problems"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "ready" && state.entries.length === 0) {
		if (filtered) {
			return (
				<EmptyState icon={<SearchXIcon />} headingLevel={2} title="No problems match this filter" />
			);
		}
		if (progress === "active") {
			return (
				<EmptyState
					icon={<HourglassIcon />}
					headingLevel={2}
					title="No problems recorded yet"
					description="Problems appear here as the scan reviews files."
				/>
			);
		}
		// Failed, cancelled and partial scans share this state. A partial scan may have reviewed every
		// file and withheld some results, so it names the scope, not a stop.
		if (progress === "stopped") {
			return (
				<EmptyState
					icon={<CircleDashedIcon />}
					headingLevel={2}
					title="No problems recorded in the work it reached"
					description="Its observations cover only the work it reached, so this does not show that the code is clean."
				/>
			);
		}
		return (
			<EmptyState
				icon={<CircleCheckIcon />}
				headingLevel={2}
				title="This scan recorded no problems"
				description={
					pairsWithoutResult > 0
						? `That does not mean every practice is met: ${pairsWithoutResult} practice and file ${pairsWithoutResult === 1 ? "pair has" : "pairs have"} no observation in this scan.`
						: undefined
				}
			/>
		);
	}
	return (
		<div className="space-y-4">
			<Table
				bordered
				aria-label="Problems in this scan"
				aria-busy={state.status === "loading" || state.loadingMore || undefined}
			>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">Problem</TableHead>
						<TableHead scope="col">File</TableHead>
						<TableHead scope="col">Practice</TableHead>
						<TableHead scope="col">Compared</TableHead>
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
							const comparison =
								entry.baselineState === null
									? undefined
									: baselineDef(entry.baselineState, entry.absentReason);
							return (
								<PracticeTableRow
									key={entry.problem.id}
									link={{
										text: "Open problem",
										name: `${entry.problem.summary}, ${citationText(entry)}, ${entry.practiceName}`,
										render: (
											<Link
												to="/w/$workspaceSlug/admin/practices/reviews/problems"
												params={{ workspaceSlug }}
												search={detailSearch(problemLevel(entry.problem.id))}
											/>
										),
									}}
								>
									<TableCell className="max-w-md">
										<div className="flex flex-wrap items-start gap-2">
											<StatusBadge def={SEVERITY_DEFS[entry.problem.severity]} />
											<span className="min-w-0 flex-1">{entry.problem.summary}</span>
										</div>
									</TableCell>
									<TableCell className="max-w-xs">
										<span className="block truncate font-mono text-xs">{citationText(entry)}</span>
									</TableCell>
									<TableCell className="max-w-48 text-sm">
										<span className="block truncate">{entry.practiceName}</span>
									</TableCell>
									<TableCell className="text-sm">
										{comparison === undefined ? (
											<span className="text-muted-foreground">Not compared</span>
										) : (
											<div className="space-y-1">
												<StatusBadge def={comparison} />
												{entry.baselineState === "ABSENT" && (
													<p className="text-xs text-muted-foreground">{comparison.description}</p>
												)}
											</div>
										)}
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
					noun="problems"
				/>
			)}
		</div>
	);
}
