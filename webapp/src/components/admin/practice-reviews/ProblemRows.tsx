import { CircleAlertIcon } from "lucide-react";

import type { ReviewProblem } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ReviewItemCell } from "@/components/practice-trace/ReviewDayTable";
import { PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import {
	RecordDayTable,
	type RecordTableColumn,
	RecordTimeCell,
} from "@/components/practice-vocabulary/RecordTable";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { StatusLabel } from "@/components/practice-vocabulary/StatusTooltip";
import { TableCell } from "@/components/ui/table";
import { formatDayTime } from "@/lib/dates";

import { RecordResults, type RecordResultsWords } from "./RecordResults";
import { RecordRowActions, ROW_OPEN_COLUMN, rowActionsColumn } from "./RecordRowActions";
import { citationLocation, citationSide } from "./ResultEvidence";
import { ResultExceptions } from "./ResultRows";
import { problemLevel } from "./review-levels";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeName } from "./ReviewPracticeLink";
import { useTracked } from "./use-tracked";

export type ProblemRowsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty"; filtered: false }
	| { status: "empty"; filtered: true; onClearFilters: () => void }
	| { status: "ready"; problems: ReviewProblem[] };

const LIST_LABEL = "Problems";

export const PROBLEM_COLUMNS: readonly RecordTableColumn[] = [
	{ label: "Recorded", className: "w-24 whitespace-nowrap" },
	{ label: "Problem" },
	{ label: "Work" },
	{ label: "Developer", className: "w-44" },
	rowActionsColumn(),
	ROW_OPEN_COLUMN,
];

const PROBLEM_WORDS: RecordResultsWords = {
	errorTitle: "We could not load problems",
	icon: CircleAlertIcon,
	emptyTitle: "No problems yet",
	filteredTitle: "No problems match these filters",
	emptyDescription: "Problems appear here when a Not met observation records them.",
};

const HIDDEN_SUMMARY = "Details hidden";

export interface ProblemRowsProps {
	state: ProblemRowsState;
	/** Placeholder rows after the problems while the next page loads. */
	loadingMore?: number;
}

export function ProblemRows({ state, loadingMore }: ProblemRowsProps) {
	const rows = state.status === "ready" ? state.problems : [];
	return (
		<RecordResults
			state={state}
			aria-label={LIST_LABEL}
			columns={PROBLEM_COLUMNS}
			words={PROBLEM_WORDS}
		>
			{(empty) => (
				<RecordDayTable
					aria-label={LIST_LABEL}
					columns={PROBLEM_COLUMNS}
					rows={rows}
					dateOf={(problem) => problem.recordedAt}
					rowKey={(problem) => problem.id}
					renderRow={(problem) => <ProblemRow problem={problem} />}
					empty={empty}
					loadingMore={loadingMore}
				/>
			)}
		</RecordResults>
	);
}

function placeOf(problem: ReviewProblem) {
	const { sourceKind, path, startLine, endLine, side } = problem;
	if (sourceKind === null || path === null) {
		return;
	}
	const place = { sourceKind, path, startLine, endLine, side };
	const location = citationLocation(place);
	if (location === undefined) {
		return;
	}
	return { location, before: side === "OLD" ? citationSide(place) : undefined };
}

export interface ProblemRowProps {
	problem: ReviewProblem;
}

export function ProblemRow({ problem }: ProblemRowProps) {
	const today = new Date(useNow());
	const tracked = useTracked({ kind: "result", id: problem.resultId });
	const summary = problem.evidenceHidden ? HIDDEN_SUMMARY : problem.summary;
	const place = placeOf(problem);
	const { container, label } = problem.item;
	const work = container === null ? label : `${container} ${label}`;
	const name = `${summary}, ${problem.practiceName}, ${work}, ${formatDayTime(problem.recordedAt, today)}`;
	return (
		<PracticeTableRow
			link={{
				text: "Open problem",
				name,
				render: <DetailStackLink entry={problemLevel(problem.id)} single />,
			}}
			variant={tracked ? "highlighted" : undefined}
			action={
				<RecordRowActions
					record={{ kind: "result", id: problem.resultId }}
					reviewId={problem.reviewId}
					name={`observation ${name}`}
				/>
			}
		>
			<RecordTimeCell
				status={SEVERITY_DEFS[problem.severity]}
				at={problem.recordedAt}
				statusTooltip={false}
			/>
			<TableCell className="align-top whitespace-normal">
				<span className="flex min-w-0 flex-col items-start gap-1.5">
					<span
						className={
							problem.evidenceHidden
								? "text-sm text-muted-foreground"
								: "max-w-md text-sm font-semibold break-words"
						}
					>
						{summary}
					</span>
					<span className="flex max-w-full flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
						<ReviewPracticeName practiceName={problem.practiceName} group={problem.group} />
						{/* In words too: the time column's glyph names it only on hover. */}
						<StatusLabel def={SEVERITY_DEFS[problem.severity]} className="text-xs" />
						{place !== undefined && <code className="break-all">{place.location}</code>}
						{place?.before !== undefined && <span>{place.before}</span>}
					</span>
					<ResultExceptions result={problem} />
				</span>
			</TableCell>
			<ReviewItemCell item={problem.item} linked={false} />
			<TableCell className="align-top whitespace-normal">
				<ReviewPerson person={problem.developer} />
			</TableCell>
		</PracticeTableRow>
	);
}
