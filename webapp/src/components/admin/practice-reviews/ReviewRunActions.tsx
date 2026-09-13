import type { AgentJob } from "@/api/types.gen";
import { isCancellable, isResultProcessingRetryable } from "@/components/admin/ai/job-utils";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";

export interface ReviewRunActionsProps {
	job: AgentJob;
	isCancelling: boolean;
	isRetrying: boolean;
	onCancel: () => void;
	onRetry: () => void;
}

export function ReviewRunActions({
	job,
	isCancelling,
	isRetrying,
	onCancel,
	onRetry,
}: ReviewRunActionsProps) {
	if (!isCancellable(job.status) && !isResultProcessingRetryable(job)) return null;
	return (
		<div className="flex gap-2">
			{isCancellable(job.status) && (
				<AlertDialog>
					<AlertDialogTrigger
						render={
							<Button variant="outline" disabled={isCancelling}>
								{isCancelling ? "Cancelling…" : "Cancel review"}
							</Button>
						}
					/>
					<AlertDialogContent>
						<AlertDialogHeader>
							<AlertDialogTitle>Cancel this review?</AlertDialogTitle>
							<AlertDialogDescription>
								The running review stops and cannot be resumed.
							</AlertDialogDescription>
						</AlertDialogHeader>
						<AlertDialogFooter>
							<AlertDialogCancel>Keep review running</AlertDialogCancel>
							<AlertDialogAction variant="destructive" disabled={isCancelling} onClick={onCancel}>
								Cancel review
							</AlertDialogAction>
						</AlertDialogFooter>
					</AlertDialogContent>
				</AlertDialog>
			)}
			{isResultProcessingRetryable(job) && (
				<AlertDialog>
					<AlertDialogTrigger
						render={
							<Button disabled={isRetrying}>
								{isRetrying ? "Retrying…" : "Retry result processing"}
							</Button>
						}
					/>
					<AlertDialogContent>
						<AlertDialogHeader>
							<AlertDialogTitle>Retry result processing?</AlertDialogTitle>
							<AlertDialogDescription>
								Process the review results again. This may retry failed publication; approval
								requirements still apply.
							</AlertDialogDescription>
						</AlertDialogHeader>
						<AlertDialogFooter>
							<AlertDialogCancel>Cancel</AlertDialogCancel>
							<AlertDialogAction disabled={isRetrying} onClick={onRetry}>
								Retry result processing
							</AlertDialogAction>
						</AlertDialogFooter>
					</AlertDialogContent>
				</AlertDialog>
			)}
		</div>
	);
}
