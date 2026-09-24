import { Link } from "@tanstack/react-router";
import { cn } from "cn";
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
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
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
		<Card role="region" aria-labelledby={`${id}-heading`}>
			<CardHeader>
				<CardTitle>
					<h3 id={`${id}-heading`}>Email invitations</h3>
				</CardTitle>
				<CardDescription>
					Only eligible members who opted in to this kind of survey email can be invited. Creating
					or editing a survey does not send email.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-3">
				{state.status === "loading" && <Skeleton className="h-24 w-full" />}
				{state.status === "error" && (
					<QueryErrorAlert
						title="Could not load email invitations"
						error={state.error}
						onRetry={state.onRetry}
					/>
				)}
				{state.status === "ready" && (
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
							{state.summary.deliveryConfigured ? (
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
							) : (
								<Link to="/admin/settings" className={cn(buttonVariants({ variant: "outline" }))}>
									Set up email
								</Link>
							)}
							<Button variant="outline" disabled={busy} onClick={state.onRefresh}>
								Refresh counts
							</Button>
						</div>
						{!state.summary.deliveryConfigured && (
							<p className="text-sm text-muted-foreground">
								Surveys still appear in Hephaestus. Set up email in instance settings to send
								invitations.
							</p>
						)}
						{state.summary.deliveryConfigured && !state.summary.remaining && (
							<p className="text-sm text-muted-foreground">
								No eligible recipients waiting for an invitation.
							</p>
						)}
						<AlertDialog
							open={confirming && state.summary.deliveryConfigured}
							onOpenChange={setConfirming}
						>
							<AlertDialogContent>
								<AlertDialogHeader>
									<AlertDialogTitle>Queue survey invitation emails?</AlertDialogTitle>
									<AlertDialogDescription>
										This requests email for up to 1,000 eligible recipients. Repeat this action if
										recipients remain. It also retries invitations that were cancelled or expired
										before the relay accepted them. Invitations already accepted by the relay are
										not sent again; cancelled reminders stay cancelled.
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
											Only after relay acceptance and while still eligible. Pausing the survey
											cancels pending reminders.
										</FieldDescription>
									</FieldContent>
								</Field>
								<AlertDialogFooter>
									<AlertDialogCancel>Cancel</AlertDialogCancel>
									<AlertDialogAction
										disabled={busy || !state.summary.remaining}
										onClick={() => {
											if (busy || !state.summary.deliveryConfigured || !state.summary.remaining) {
												return;
											}
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
			</CardContent>
		</Card>
	);
}
