import { useId, useState } from "react";
import { useSpinDelay } from "spin-delay";

import type { SurveyEmailInvitationSummary } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldLabel, FieldDescription, FieldContent } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export type SurveyEmailInvitationsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			summary: SurveyEmailInvitationSummary;
			isPending: boolean;
			onQueue: (sendReminder: boolean) => void;
			onRefresh: () => void;
	  };

export function AdminSurveyEmailInvitations({ state }: { state: SurveyEmailInvitationsState }) {
	const id = useId();
	const [confirming, setConfirming] = useState(false);
	const [sendReminder, setSendReminder] = useState(false);
	const showSpinner = useSpinDelay(state.status === "ready" && state.isPending, {
		delay: 1000,
		minDuration: 500,
	});
	const busy = state.status === "ready" && (state.isPending || showSpinner);
	return (
		<section className="space-y-3 rounded-lg border p-4" aria-labelledby={`${id}-heading`}>
			<h3 id={`${id}-heading`} className="text-lg font-semibold">
				Email invitations
			</h3>
			<p className="text-sm text-muted-foreground">
				Only eligible members who opted in to this kind of survey email can be invited. Creating or
				editing a survey does not send email.
			</p>
			{state.status === "loading" ? (
				<Skeleton className="h-24 w-full" />
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Could not load email invitations"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : (
				<>
					<dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
						{[
							{ label: "Eligible now", value: state.summary.eligible },
							{ label: "Already requested", value: state.summary.alreadyRequested },
							{ label: "Accepted by relay", value: state.summary.accepted },
							{ label: "Remaining", value: state.summary.remaining },
						].map(({ label, value }) => (
							<div key={label}>
								<dt className="text-xs text-muted-foreground">{label}</dt>
								<dd className="font-semibold">{value}</dd>
							</div>
						))}
					</dl>
					<p className="text-sm text-muted-foreground">
						Relay acceptance does not confirm inbox delivery. Already requested includes cancelled
						requests; it is not a pending-delivery count. Eligibility is checked again before
						sending.
					</p>
					<div className="flex flex-wrap gap-2">
						<Button
							disabled={busy || !state.summary.remaining}
							onClick={() => {
								setSendReminder(false);
								setConfirming(true);
							}}
						>
							{showSpinner && <Spinner />}
							{showSpinner ? "Queuing…" : "Queue email invitations…"}
						</Button>
						<Button variant="outline" disabled={busy} onClick={state.onRefresh}>
							Refresh counts
						</Button>
					</div>
					{!state.summary.remaining && (
						<p className="text-sm text-muted-foreground">
							No eligible recipients waiting for an invitation.
						</p>
					)}
					<AlertDialog open={confirming} onOpenChange={setConfirming}>
						<AlertDialogContent>
							<AlertDialogHeader>
								<AlertDialogTitle>Queue survey invitation emails?</AlertDialogTitle>
								<AlertDialogDescription>
									This requests email for up to 1,000 eligible recipients. Repeat this action if
									recipients remain. After resuming a survey, it also retries cancelled invitations
									that were not sent. Invitations already accepted by the relay are not sent again;
									cancelled reminders stay cancelled.
								</AlertDialogDescription>
							</AlertDialogHeader>
							<Field orientation="horizontal">
								<Checkbox
									id={`${id}-reminder`}
									checked={sendReminder}
									onCheckedChange={setSendReminder}
									aria-describedby={`${id}-reminder-help`}
								/>
								<FieldContent>
									<FieldLabel htmlFor={`${id}-reminder`}>
										Send one reminder after 72 hours if unanswered
									</FieldLabel>
									<FieldDescription id={`${id}-reminder-help`}>
										Only after relay acceptance and while still eligible. Pausing the survey cancels
										pending reminders.
									</FieldDescription>
								</FieldContent>
							</Field>
							<AlertDialogFooter>
								<AlertDialogCancel>Cancel</AlertDialogCancel>
								<AlertDialogAction
									disabled={busy || !state.summary.remaining}
									onClick={() => {
										if (busy || !state.summary.remaining) return;
										setConfirming(false);
										state.onQueue(sendReminder);
									}}
								>
									Queue invitations
								</AlertDialogAction>
							</AlertDialogFooter>
						</AlertDialogContent>
					</AlertDialog>
				</>
			)}
		</section>
	);
}
