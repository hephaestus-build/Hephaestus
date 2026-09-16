import { CircleAlertIcon } from "lucide-react";

import type { PracticeEvidenceOutcome, PracticeEvidenceSourceOption } from "@/api/types.gen";
import {
	evidenceSourceLabel,
	readinessReasonLabel,
} from "@/components/admin/practice-catalog/evidence-presentation";
import { Progress } from "@/components/ui/progress";

export interface PracticeEvidenceOutcomeSummaryProps {
	outcome: PracticeEvidenceOutcome;
	sources: readonly PracticeEvidenceSourceOption[];
}

/**
 * What these requirements cost in practice. The number an operator is after is the share of reviews
 * they turned away, so the bar answers that before the words are read; the words then say which
 * source was missing.
 */
export function PracticeEvidenceOutcomeSummary({
	outcome,
	sources,
}: PracticeEvidenceOutcomeSummaryProps) {
	if (outcome.consideredReviews === 0) {
		return null;
	}
	const skipped = outcome.consideredReviews - outcome.reviewedCount;
	const reviews = (count: number) => `${count} ${count === 1 ? "review" : "reviews"}`;
	return (
		<div className="space-y-2 rounded-lg border p-4">
			<div className="flex flex-wrap items-baseline justify-between gap-x-3">
				<p className="font-medium">On recent reviews</p>
				<p className="text-sm text-muted-foreground tabular-nums">
					{outcome.reviewedCount} of {reviews(outcome.consideredReviews)} ran
				</p>
			</div>
			<Progress
				value={(outcome.reviewedCount / outcome.consideredReviews) * 100}
				aria-label={`Reviews these requirements let through, out of the last ${outcome.consideredReviews} that reached this practice`}
			/>
			{skipped === 0 ? (
				<p className="text-sm text-muted-foreground">
					These requirements were met every time a review reached this practice.
				</p>
			) : (
				<>
					{/* "Skipped in N reviews" rather than "N reviews were skipped": one review makes the
					    second read "1 review were skipped". */}
					<p className="text-sm text-muted-foreground">
						Skipped in {reviews(skipped)}, because the evidence was not there to review against.
					</p>
					{outcome.blockersObserved.length > 0 && (
						<ul className="space-y-1 text-sm text-muted-foreground">
							{outcome.blockersObserved.map((blocker) => (
								<li
									key={`${blocker.sourceKind ?? "practice"}:${blocker.reasonCode}`}
									className="flex items-start gap-2"
								>
									<CircleAlertIcon className="mt-0.5 size-3.5 shrink-0 text-warning" aria-hidden />
									<span>
										{blocker.sourceKind
											? `${evidenceSourceLabel(blocker.sourceKind, sources)} — ${readinessReasonLabel(blocker.reasonCode)}`
											: readinessReasonLabel(blocker.reasonCode)}{" "}
										({reviews(blocker.reviewsAffected)})
									</span>
								</li>
							))}
						</ul>
					)}
				</>
			)}
		</div>
	);
}
