import type { AgentJob, Practice } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { holdReasonCopy, jobWait } from "./job-utils";
import { practiceLevel, reviewLevel } from "./review-levels";

export interface ReviewRunNoticesProps {
	job: AgentJob;
	practices: Pick<Practice, "slug" | "name">[] | undefined;
	/**
	 * The review stopped early but still produced something, so what is listed below it may be part
	 * of what it would have found. A run that stopped early and produced *nothing* says so in the
	 * empty state instead, and does not need this said twice.
	 */
	outputMayBeIncomplete: boolean;
}

/**
 * What a reader has to know about a run before they read its output: that the output is partial,
 * that the run is parked rather than broken, or that some ready practices were not asked because an
 * earlier review had already answered them — the reason a run asks fewer practices than were ready.
 *
 * A hold is deliberately never phrased as a failure — it ends by itself, and an operator who reads
 * "failed" goes looking for something to fix that is not there.
 */
export function ReviewRunNotices({ job, practices, outputMayBeIncomplete }: ReviewRunNoticesProps) {
	const now = useNow();
	const wait = jobWait(job, now);
	const hold = wait?.kind === "hold" ? holdReasonCopy(wait.reason) : undefined;
	const answered = job.answeredPractices ?? [];
	return (
		<>
			{answered.length > 0 && (
				<Alert>
					<AlertTitle>
						{answered.length === 1
							? "1 practice was answered by an earlier review"
							: `${answered.length} practices were answered by an earlier review`}
					</AlertTitle>
					<AlertDescription>
						<p>
							A completed review had already answered these on exactly the same code, so this review
							did not ask them again. Open the earlier review to see its observations.
						</p>
						<ul className="list-disc pl-5">
							{answered.map((practice) => (
								<li key={practice.practiceSlug}>
									<InlineLink
										render={<DetailStackLink entry={practiceLevel(practice.practiceSlug)} />}
									>
										{practices?.find((item) => item.slug === practice.practiceSlug)?.name ??
											practice.practiceSlug}
									</InlineLink>
									, answered in{" "}
									<InlineLink render={<DetailStackLink entry={reviewLevel(practice.reviewId)} />}>
										an earlier review
									</InlineLink>
								</li>
							))}
						</ul>
					</AlertDescription>
				</Alert>
			)}
			{outputMayBeIncomplete && (
				<Alert variant={job.status === "FAILED" ? "destructive" : "default"}>
					<AlertTitle>Review output may be incomplete</AlertTitle>
					<AlertDescription>
						The review ended early. The observations and feedback below may be only part of what it
						would have found.
					</AlertDescription>
				</Alert>
			)}
			{hold && (
				<Alert variant="warning">
					<AlertTitle>{hold.label}</AlertTitle>
					<AlertDescription>{hold.detail}</AlertDescription>
				</Alert>
			)}
		</>
	);
}
