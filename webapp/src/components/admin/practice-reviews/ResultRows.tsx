import { ScanSearchIcon } from "lucide-react";

import type { ReviewResult } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ReviewItemCell } from "@/components/practice-trace/ReviewDayTable";
import { ClaimCurrentnessBadge } from "@/components/practice-vocabulary/ClaimCurrentness";
import { OUTCOME_DEFS, problemCount } from "@/components/practice-vocabulary/outcome-defs";
import { PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import {
	RecordDayTable,
	RecordGroupedTable,
	type RecordTableColumn,
	RecordTimeCell,
} from "@/components/practice-vocabulary/RecordTable";
import { RESULT_MARK_DEFS } from "@/components/practice-vocabulary/result-mark-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { StatusLabel } from "@/components/practice-vocabulary/StatusTooltip";
import { TableCell } from "@/components/ui/table";
import { formatDayTime } from "@/lib/dates";

import { resultSummary } from "./result-words";

import { RecordResults, type RecordResultsWords } from "./RecordResults";
import { RecordRowActions, ROW_OPEN_COLUMN, rowActionsColumn } from "./RecordRowActions";
import { resultLevel } from "./review-levels";
import { ResultOriginNote, resultOutcomeDef } from "./ReviewBadges";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeName } from "./ReviewPracticeLink";
import { useTracked } from "./use-tracked";

/** See `FeedbackResultsState`: a filtered empty state has to carry the way out of itself. */
export type ResultRowsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty"; filtered: false }
	| { status: "empty"; filtered: true; onClearFilters: () => void }
	| { status: "ready"; results: ReviewResult[] };

export interface ResultRowsProps {
	state: ResultRowsState;
	/**
	 * `false` for an order other than time, such as "Most actionable first": the rows then sit in one
	 * body and each names its own day, since headings per day would repeat a day each time it came
	 * back.
	 */
	byDay?: boolean;
	/** Placeholder rows after the results while the next page loads. */
	loadingMore?: number;
}

const LIST_LABEL = "Observations";

export const RESULT_COLUMNS: readonly RecordTableColumn[] = [
	{ label: "Recorded", className: "w-24 whitespace-nowrap" },
	{ label: "Observation" },
	{ label: "Work" },
	{ label: "Developer", className: "w-44" },
	rowActionsColumn(),
	ROW_OPEN_COLUMN,
];

const RESULT_WORDS: RecordResultsWords = {
	errorTitle: "We could not load observations",
	icon: ScanSearchIcon,
	emptyTitle: "No observations yet",
	filteredTitle: "No observations match these filters",
	emptyDescription: "Observations appear after a practice review completes.",
};

export function ResultRows({ state, byDay = true, loadingMore }: ResultRowsProps) {
	const rows = state.status === "ready" ? state.results : [];
	return (
		<RecordResults
			state={state}
			aria-label={LIST_LABEL}
			columns={RESULT_COLUMNS}
			words={RESULT_WORDS}
		>
			{(empty) =>
				byDay ? (
					<RecordDayTable
						aria-label={LIST_LABEL}
						columns={RESULT_COLUMNS}
						rows={rows}
						dateOf={(result) => result.recordedAt}
						rowKey={(result) => result.id}
						renderRow={(result) => <ResultRow result={result} />}
						empty={empty}
						loadingMore={loadingMore}
					/>
				) : (
					<RecordGroupedTable
						aria-label={LIST_LABEL}
						columns={RESULT_COLUMNS}
						groups={byActionability(rows)}
						rowKey={(result) => result.id}
						renderRow={(result) => <ResultRow result={result} withDay />}
						empty={empty}
						loadingMore={loadingMore}
					/>
				)
			}
		</RecordResults>
	);
}

function actionabilityRank(result: ReviewResult): string {
	if (result.outcome === "NOT_MET") {
		return result.severity === null
			? OUTCOME_DEFS.NOT_MET.label
			: `${OUTCOME_DEFS.NOT_MET.label} · ${SEVERITY_DEFS[result.severity].label}`;
	}
	if (result.outcome === "MET") {
		return OUTCOME_DEFS.MET.label;
	}
	return `${OUTCOME_DEFS.DOES_NOT_APPLY.label} or ${OUTCOME_DEFS.COULD_NOT_DETERMINE.label.toLowerCase()}`;
}

// Preserve server ranks across pages; a returning rank must join its existing table body.
export function byActionability(
	rows: readonly ReviewResult[],
): { label: string; rows: ReviewResult[] }[] {
	const groups: { label: string; rows: ReviewResult[] }[] = [];
	for (const row of rows) {
		const label = actionabilityRank(row);
		const group = groups.find((candidate) => candidate.label === label);
		if (group) {
			group.rows.push(row);
		} else {
			groups.push({ label, rows: [row] });
		}
	}
	return groups;
}

export interface ResultExceptionsProps {
	result: Pick<ReviewResult, "currentness" | "markedIncorrectAt">;
}

export function ResultExceptions({ result }: ResultExceptionsProps) {
	if (result.currentness === "CURRENT" && result.markedIncorrectAt === null) {
		return null;
	}
	return (
		<span className="flex flex-wrap items-center gap-1.5">
			<ClaimCurrentnessBadge currentness={result.currentness} />
			{result.markedIncorrectAt !== null && <StatusBadge def={RESULT_MARK_DEFS.INCORRECT} />}
		</span>
	);
}

export interface ResultRowProps {
	result: ReviewResult;
	withDay?: boolean;
}

// Reserve row severity labels for costly problems; ordinary severities remain in the detail sheet.
function costlySeverity(result: ReviewResult) {
	return result.outcome === "NOT_MET" &&
		(result.severity === "CRITICAL" || result.severity === "MAJOR")
		? SEVERITY_DEFS[result.severity]
		: undefined;
}

export function ResultRow({ result, withDay = false }: ResultRowProps) {
	const today = new Date(useNow());
	const tracked = useTracked({ kind: "result", id: result.id });
	const severity = costlySeverity(result);
	const summary = resultSummary({ summary: result.summary ?? undefined });
	return (
		<PracticeTableRow
			link={{
				text: "Open observation",
				name: `${summary}, ${formatDayTime(result.recordedAt, today)}`,
				render: <DetailStackLink entry={resultLevel(result.id)} single />,
			}}
			variant={tracked ? "highlighted" : undefined}
			action={
				<RecordRowActions
					record={{ kind: "result", id: result.id }}
					reviewId={result.reviewId}
					name={`observation ${summary}`}
				/>
			}
		>
			<RecordTimeCell
				status={resultOutcomeDef(result.outcome, result.problemCount)}
				at={result.recordedAt}
				statusTooltip={false}
				withDay={withDay}
			/>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1.5">
					<span className="max-w-md text-sm font-semibold break-words">{summary}</span>
					<span className="flex max-w-full flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
						<ReviewPracticeName practiceName={result.practiceName} group={null} />
						{result.outcome === "NOT_MET" && result.problemCount > 0 && (
							<span>{problemCount(result.problemCount)}</span>
						)}
						{severity && <StatusLabel def={severity} className="text-xs" />}
						<ResultOriginNote origin={result.origin} />
					</span>
					<ResultExceptions result={result} />
				</span>
			</TableCell>
			<ReviewItemCell item={result.item} linked={false} />
			<TableCell className="align-top whitespace-normal">
				<ReviewPerson person={result.developer} />
			</TableCell>
		</PracticeTableRow>
	);
}
