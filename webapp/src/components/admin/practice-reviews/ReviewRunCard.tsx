import { CopyIcon } from "lucide-react";
import { toast } from "sonner";

import type { AgentJob } from "@/api/types.gen";
import { formatTokens, JOB_TYPE_LABELS } from "@/components/admin/usage/usage-utils";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Button } from "@/components/ui/button";
import { hasText } from "@/lib/text";
import { modelLabel } from "./job-utils";

import { ReviewFact, ReviewFactGrid } from "./ReviewDetailHeader";

export interface ReviewRunCardProps {
	job: AgentJob;
}

/**
 * On the page rather than behind a disclosure: the model and the token count are what an operator
 * checks when a review costs more than it should or answers worse than it used to.
 */
export function ReviewRunCard({ job }: ReviewRunCardProps) {
	const copyConfiguration = async () => {
		try {
			await navigator.clipboard.writeText(JSON.stringify(job.configSnapshot, null, 2));
			toast.success("Configuration copied");
		} catch {
			toast.error("Could not copy to clipboard");
		}
	};

	return (
		<section aria-labelledby="run-card-heading" className="space-y-3">
			<div className="flex flex-wrap items-center justify-between gap-2">
				<h3 id="run-card-heading" className="text-lg font-semibold">
					How this review ran
				</h3>
				<Button
					variant="outline"
					size="sm"
					onClick={() => {
						void copyConfiguration();
					}}
				>
					<CopyIcon aria-hidden />
					Copy configuration
				</Button>
			</div>
			<ReviewFactGrid>
				<ReviewFact label="Reviewed as">{JOB_TYPE_LABELS[job.jobType]}</ReviewFact>
				<ReviewFact label="Model">{modelLabel(job)}</ReviewFact>
				<ReviewFact label="Finished">
					{job.completedAt ? <RelativeTime value={job.completedAt} /> : "Not yet"}
				</ReviewFact>
				<ReviewFact label="Model calls">{formatTokens(job.llmTotalCalls)}</ReviewFact>
				<ReviewFact label="Tokens read">{formatTokens(job.llmTotalInputTokens)}</ReviewFact>
				<ReviewFact label="Tokens written">
					{/* Reasoning tokens are billed as output, so they are named as a part of it. On a row of
					    their own they read as a third quantity to be added to the other two. */}
					{formatTokens(job.llmTotalOutputTokens)}
					{job.llmTotalReasoningTokens != null && job.llmTotalReasoningTokens > 0 && (
						<span className="text-muted-foreground">
							{" "}
							({formatTokens(job.llmTotalReasoningTokens)} of it reasoning)
						</span>
					)}
				</ReviewFact>
			</ReviewFactGrid>
			{hasText(job.errorMessage) && (
				<div className="space-y-1 rounded-lg border border-destructive/40 p-3">
					<p className="text-sm font-medium">What went wrong</p>
					<pre className="max-h-48 overflow-auto text-xs break-words whitespace-pre-wrap text-muted-foreground">
						{job.errorMessage}
					</pre>
				</div>
			)}
		</section>
	);
}
