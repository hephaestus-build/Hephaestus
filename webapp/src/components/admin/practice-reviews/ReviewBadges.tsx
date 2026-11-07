import type { ReviewResult } from "@/api/types.gen";
import { definitionFor, type StatusDef } from "@/components/common/status-def";

import { StatusBadge } from "@/components/common/StatusBadge";
import { OUTCOME_DEFS, outcomeLabel } from "@/components/practice-vocabulary/outcome-defs";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { StatusLabel } from "@/components/practice-vocabulary/StatusTooltip";

export function resultOutcomeDef(
	outcome: ReviewResult["outcome"],
	problems: number | undefined,
): StatusDef {
	return { ...definitionFor(OUTCOME_DEFS, outcome), label: outcomeLabel(outcome, problems) };
}

export interface ResultOutcomeProps {
	result: Pick<ReviewResult, "outcome" | "severity">;
	/** How many problems the result names; omitted where they are not known. */
	problems?: number;
}

export function ResultOutcome({ result, problems }: ResultOutcomeProps) {
	const severity = resultSeverity(result);
	return (
		<span className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
			<StatusLabel def={resultOutcomeDef(result.outcome, problems)} />
			{severity && <StatusLabel def={severity} />}
		</span>
	);
}

export function isExceptionStatus(def: StatusDef): boolean {
	return def.badgeVariant === "destructive" || def.badgeVariant === "warning";
}

/**
 * A record's status on a level: a badge when it is an exception, otherwise its icon and words as
 * plain text, so the badges that remain are the ones to look at.
 */
export function ReviewStatusMark({ def }: { def: StatusDef }) {
	return isExceptionStatus(def) ? <StatusBadge def={def} /> : <StatusLabel def={def} />;
}

// Severity describes the cost of Not met problems; it must not label Met results.
export function resultSeverity(
	result: Pick<ReviewResult, "outcome" | "severity">,
): StatusDef | undefined {
	return result.outcome === "NOT_MET" && result.severity
		? definitionFor(SEVERITY_DEFS, result.severity)
		: undefined;
}

export function ResultOriginNote({ origin }: { origin: ReviewResult["origin"] }) {
	if (origin === "EVENT") {
		return null;
	}
	return <span>{definitionFor(REVIEW_ORIGIN_DEFS, origin).label}</span>;
}
