import type { SyncJob } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Spinner } from "@/components/ui/spinner";

import { JOB_TYPE_LABEL, jobProgress, phaseLabel } from "./sync-format";

export interface ActiveJobProgressProps {
	job?: SyncJob | null;
}

/**
 * What the running job is doing right now, in two layers matching the server's progress contract:
 * `itemsProcessed`/`itemsTotal` are job-global and drive the bar, while `progress.currentStep` is the
 * phase-local sentence ("Backfilling ls1intum/Artemis — issues #4812 → #3200") and `progress.phase`
 * is the chip. `itemsTotal: null` means "not yet known" (a backfill has no high-water marks until its
 * first batch lands), and that path stays a spinner rather than inventing a denominator.
 */
export function ActiveJobProgress({ job }: ActiveJobProgressProps) {
	if (!job) {
		return null;
	}

	const processed = job.itemsProcessed ?? 0;
	const total = job.itemsTotal;
	const { currentStep, phase } = jobProgress(job);
	const isDeterminate = total != null && total > 0;

	// The step is long by design (repo name plus an id range), so it gets its own line under the bar;
	// sharing the bar's row would truncate exactly the part that says what is happening.
	return (
		<div className="space-y-1.5 text-sm text-muted-foreground">
			<div className="flex items-center gap-2">
				{isDeterminate ? (
					<>
						<Progress
							value={Math.min(100, Math.round((processed / total) * 100))}
							className="w-32 shrink-0"
							aria-label="Sync progress"
							getAriaValueText={() =>
								`${processed.toLocaleString()} of ${total.toLocaleString()} items${
									currentStep ? `, ${currentStep}` : ""
								}`
							}
						/>
						<span className="shrink-0 tabular-nums">
							{processed.toLocaleString()}/{total.toLocaleString()}
						</span>
					</>
				) : (
					<>
						<Spinner className="size-4 shrink-0" />
						{!currentStep && <span>{JOB_TYPE_LABEL[job.type]} running…</span>}
					</>
				)}
				{phase && (
					<Badge variant="secondary" className="shrink-0">
						{phaseLabel(phase)}
					</Badge>
				)}
			</div>
			{currentStep && (
				<p className="truncate" title={currentStep}>
					{currentStep}
				</p>
			)}
		</div>
	);
}
