import type { AgentJob } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { holdReasonCopy, jobWait } from "./job-utils";

export interface ReviewRunNoticesProps {
	job: AgentJob;
	/**
	 * The review stopped early but still produced something, so what is listed below it may be part
	 * of what it would have found. A run that stopped early and produced *nothing* says so in the
	 * empty state instead, and does not need this said twice.
	 */
	outputMayBeIncomplete: boolean;
}

/**
 * What a reader has to know about a run before they read its output: that the output is partial, or
 * that the run is parked rather than broken.
 *
 * A hold is deliberately never phrased as a failure — it ends by itself, and an operator who reads
 * "failed" goes looking for something to fix that is not there.
 */
export function ReviewRunNotices({ job, outputMayBeIncomplete }: ReviewRunNoticesProps) {
	const now = useNow();
	const wait = jobWait(job, now);
	const hold = wait?.kind === "hold" ? holdReasonCopy(wait.reason) : undefined;
	return (
		<>
			{outputMayBeIncomplete && (
				<Alert variant={job.status === "FAILED" ? "destructive" : "default"}>
					<AlertTitle>Review output may be incomplete</AlertTitle>
					<AlertDescription>The review ended before it completed.</AlertDescription>
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
