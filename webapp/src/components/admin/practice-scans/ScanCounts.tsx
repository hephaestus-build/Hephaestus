import type { ReactNode } from "react";

import type { ScanCounts as ScanCountsData } from "@/api/types.gen";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { SCAN_ENTRY_DEFS } from "@/components/practice-vocabulary/scan-entry-defs";

import type { ScanProgress } from "./scan-display";

export interface ScanCountsProps {
	counts: ScanCountsData;
	recorded: boolean;
	progress: ScanProgress;
	/** The scan stopped before it counted any practice and file pair, so it shows no figures. */
	countedNothing: boolean;
}

/** A figure the scan never reached. A zero there would read as a clean result. */
export function NotCounted() {
	return (
		<>
			<span aria-hidden>—</span>
			<span className="sr-only">Not counted</span>
		</>
	);
}

function Figure({ label, value, note }: { label: string; value: ReactNode; note?: string }) {
	return (
		<div className="min-w-0 space-y-0.5">
			<dt className="text-xs text-muted-foreground">{label}</dt>
			<dd className="text-lg font-semibold tabular-nums">
				{value}
				{note !== undefined && (
					<>
						{" "}
						<span className="text-xs font-normal text-muted-foreground">{note}</span>
					</>
				)}
			</dd>
		</div>
	);
}

// File counts are distinct files; other counts are practice-file pairs. Unknown totals cannot supply percentages.
export function ScanCounts({ counts, recorded, progress, countedNothing }: ScanCountsProps) {
	// A closed scan with no total never listed every file; "yet" would promise a figure that will not come.
	const unknown = progress === "active" ? "Not known yet" : "Not known";
	const figure = (value: number) => (countedNothing ? <NotCounted /> : value);
	return (
		<section aria-label="Counts" className="space-y-4 rounded-lg border p-4">
			<dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
				<Figure label="Files" value={counts.files ?? unknown} />
				<Figure label="Practice and file pairs" value={counts.practiceItemPairs ?? unknown} />
			</dl>
			<dl className="grid grid-cols-2 gap-4 border-t pt-4 sm:grid-cols-4 lg:grid-cols-6">
				<Figure
					label={SCAN_ENTRY_DEFS.JUDGED.label}
					value={figure(counts.reviewed - counts.reused)}
				/>
				<Figure label={OUTCOME_DEFS.MET.label} value={figure(counts.met)} />
				<Figure label={OUTCOME_DEFS.NOT_MET.label} value={figure(counts.notMet)} />
				<Figure label={OUTCOME_DEFS.DOES_NOT_APPLY.label} value={figure(counts.doesNotApply)} />
				<Figure
					label={OUTCOME_DEFS.COULD_NOT_DETERMINE.label}
					value={figure(counts.couldNotDetermine)}
				/>
				<Figure
					label="Problems"
					value={figure(counts.problems)}
					note={counts.openProblems === counts.problems ? undefined : `${counts.openProblems} open`}
				/>
				<Figure label={SCAN_ENTRY_DEFS.REUSED.label} value={figure(counts.reused)} />
				<Figure label={SCAN_ENTRY_DEFS.WITHHELD.label} value={figure(counts.withheld)} />
				<Figure label={SCAN_ENTRY_DEFS.NOT_REVIEWED.label} value={figure(counts.notReviewed)} />
			</dl>
			<p className="text-sm text-muted-foreground">
				{countedNothing
					? "The scan stopped before it reviewed any file, so it has no counts."
					: `Each file can be reviewed against more than one practice. Reviewed, reused, withheld and not reviewed count practice and file pairs. Reviewed counts only new observations from this scan.${recorded ? " As recorded when the scan closed." : ""}`}
			</p>
		</section>
	);
}
