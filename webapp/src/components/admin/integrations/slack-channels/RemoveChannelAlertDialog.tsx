import { useState } from "react";

import type { SlackMonitoredChannel } from "@/api/types.gen";
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
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";

export interface RemoveChannelAlertDialogProps {
	/** Non-null drives the open state and names the channel being erased. */
	channel: SlackMonitoredChannel | null;
	onOpenChange: (open: boolean) => void;
	/** Confirm removal. Resolves on success (dialog closes), rejects to keep it open. */
	onConfirm: (input: { slackChannelId: string; reason?: string }) => Promise<void> | void;
}

/**
 * Destructive confirmation for a terminal erase. A channel that never got past PENDING has
 * nothing collected yet (no announcement was ever posted), so it gets a lighter variant:
 * accurate copy and no type-to-confirm gate. Every other state has real collected data, so the
 * confirm action stays disabled until the admin types the stable Slack channel ID.
 */
export function RemoveChannelAlertDialog({
	channel,
	onOpenChange,
	onConfirm,
}: RemoveChannelAlertDialogProps) {
	const [confirmText, setConfirmText] = useState("");
	const [reason, setReason] = useState("");
	const [mismatch, setMismatch] = useState(false);
	const [submitting, setSubmitting] = useState(false);

	const label = channel ? (channel.channelName ?? channel.slackChannelId) : "";
	const channelId = channel?.slackChannelId ?? "";
	// Nothing has been collected from a channel that never got past PENDING — the consent
	// announcement (and therefore any reading) only happens on first activation.
	const nothingCollected = channel?.consentState === "PENDING";
	const matches = nothingCollected || confirmText.trim() === channelId;

	// Reset the fields on close rather than remounting via a changing `key`, which would break the
	// AlertDialog's exit animation. The next open starts from a clean slate.
	function handleOpenChange(next: boolean) {
		if (!next) {
			setConfirmText("");
			setReason("");
			setMismatch(false);
			setSubmitting(false);
		}
		onOpenChange(next);
	}

	async function confirm() {
		if (!channel || submitting) {
			return;
		}
		// The confirm action stays enabled and validates here: a disabled button with no stated
		// reason leaves the admin guessing which of the two fields is wrong.
		if (!matches) {
			setMismatch(true);
			return;
		}

		setMismatch(false);
		setSubmitting(true);
		try {
			await onConfirm({
				slackChannelId: channel.slackChannelId,
				reason: reason.trim().length > 0 ? reason.trim() : undefined,
			});
			handleOpenChange(false);
		} catch {
			// Rejection = keep the dialog open. The mutation's onError already surfaced the
			// toast, so swallow here rather than let it escape as an unhandled rejection.
		}
		setSubmitting(false);
	}

	let actionLabel = "Remove & erase";
	if (submitting) {
		actionLabel = "Removing…";
	} else if (nothingCollected) {
		actionLabel = "Remove";
	}

	return (
		<AlertDialog open={channel != null} onOpenChange={handleOpenChange}>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogTitle>
						{nothingCollected ? `Remove #${label}?` : `Remove #${label} and erase its data?`}
					</AlertDialogTitle>
					<AlertDialogDescription>
						{nothingCollected ? (
							<>
								This stops the setup for this channel. Nothing has been collected from it yet — no
								announcement was ever posted. This cannot be undone.
							</>
						) : (
							<>
								This permanently deletes <strong>all messages collected</strong> from this channel
								and every practice observation derived from them. This cannot be undone.
							</>
						)}
					</AlertDialogDescription>
				</AlertDialogHeader>

				<div className="space-y-4">
					{!nothingCollected && (
						<Field data-invalid={mismatch}>
							<FieldLabel htmlFor="remove-slack-confirm">
								Type the channel ID <span className="font-mono font-medium">{channelId}</span> to
								confirm
							</FieldLabel>
							<Input
								id="remove-slack-confirm"
								value={confirmText}
								disabled={submitting}
								onChange={(e) => {
									setConfirmText(e.target.value);
									setMismatch(false);
								}}
								autoComplete="off"
								autoCapitalize="off"
								spellCheck={false}
								aria-invalid={mismatch}
							/>
							{mismatch && (
								<FieldError>
									That does not match. Type the channel ID exactly: {channelId}
								</FieldError>
							)}
						</Field>
					)}

					<Field>
						<FieldLabel htmlFor="remove-slack-reason">Reason (optional)</FieldLabel>
						<Textarea
							id="remove-slack-reason"
							value={reason}
							disabled={submitting}
							onChange={(e) => setReason(e.target.value)}
							placeholder="Recorded in the immutable audit trail."
						/>
						<FieldDescription>Kept in the consent history for accountability.</FieldDescription>
					</Field>
				</div>

				<AlertDialogFooter>
					<AlertDialogCancel disabled={submitting}>Cancel</AlertDialogCancel>
					<AlertDialogAction
						variant="destructive"
						disabled={submitting}
						onClick={() => {
							void confirm();
						}}
					>
						{actionLabel}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
