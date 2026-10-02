import { useId } from "react";

import { ASSESSMENT_STATUS_DEFS } from "@/components/practice-vocabulary/assessment-status-defs";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";

import { cn } from "cn";
import { statusDefOr } from "~/components/common/status";
import { INDENT, ListSkeleton, LoadError, Truncated } from "~/components/report/report-parts";
import type { Loadable } from "~/components/report/report-summary";
import type { ObservationPage, ObservationRow } from "~/shared/review-context";

export interface ObservationListProps {
	observations: Loadable<ObservationPage>;
	onRetry: () => void;
}

const TONE = { POSITIVE: "text-success", NEGATIVE: "text-destructive" } as const;

/**
 * What one observation concluded, as the web app's registries say it: a positive or negative outcome
 * with its severity, or the status under which nothing was settled. The icon carries the outcome
 * without colour; the label is there for a screen reader and a tooltip.
 */
function verdict(row: ObservationRow) {
	if (row.assessmentStatus === "ASSESSED" && row.outcome !== undefined) {
		const def = OUTCOME_DEFS[row.outcome];
		const severity =
			row.outcome === "NEGATIVE" && row.severity !== undefined
				? statusDefOr(SEVERITY_DEFS, row.severity).label
				: undefined;
		return { def, tone: TONE[row.outcome], severity };
	}
	return {
		def: statusDefOr(ASSESSMENT_STATUS_DEFS, row.assessmentStatus),
		tone: "text-muted-foreground",
		severity: undefined,
	};
}

function Observation({ row }: { row: ObservationRow }) {
	const { def, tone, severity } = verdict(row);
	const Icon = def.icon;
	return (
		<li className={cn(INDENT, "flex min-w-0 items-start gap-2 py-1.5 text-sm")}>
			<Icon aria-hidden className={cn("mt-0.5 size-4 shrink-0", tone)} />
			<div className="flex min-w-0 flex-1 flex-col">
				<span className="flex flex-wrap items-baseline gap-x-2">
					<span className="font-medium break-words">{row.practiceName}</span>
					<span className="text-xs text-muted-foreground">
						{def.label}
						{severity === undefined ? null : ` · ${severity}`}
						{row.claimCurrentness === "CURRENT" ? null : " · no longer current"}
					</span>
				</span>
				<span className="break-words text-muted-foreground">{row.summary}</span>
			</div>
		</li>
	);
}

/**
 * The reader's own observations on this work, as the review concluded them — each practice, its
 * outcome and one sentence. Why and the evidence behind each are the web app's to show; here nothing
 * opens further.
 */
export function ObservationList({ observations, onRetry }: ObservationListProps) {
	const heading = useId();
	let body;
	if (observations.status === "loading") {
		body = <ListSkeleton />;
	} else if (observations.status === "error") {
		body = <LoadError message={observations.message} onRetry={onRetry} />;
	} else if (observations.data.rows.length === 0) {
		body = (
			<p className={cn(INDENT, "py-1.5 text-sm text-muted-foreground")}>
				No observations about your work here.
			</p>
		);
	} else {
		const { rows, total } = observations.data;
		body = (
			<>
				<Truncated items={rows} render={(row) => <Observation key={row.id} row={row} />} />
				{total > rows.length ? (
					<p className={cn(INDENT, "pb-1 text-xs text-muted-foreground")}>
						The first {rows.length} of {total}, most severe first.
					</p>
				) : null}
			</>
		);
	}
	return (
		<section aria-labelledby={heading} className="border-t border-border py-1">
			<h3
				id={heading}
				className={cn(INDENT, "pt-2 pb-0.5 text-xs font-semibold text-muted-foreground")}
			>
				Your observations
			</h3>
			{body}
		</section>
	);
}
