import { MegaphoneIcon } from "lucide-react";
import { useState } from "react";

import type { SlackMonitoredChannel } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";

export interface ActivateChannelDialogProps {
	/** Non-null drives the open state and names the channel being activated. */
	channel: SlackMonitoredChannel | null;
	onOpenChange: (open: boolean) => void;
	/** Confirm activation. Resolves on success (dialog closes), rejects to keep it open. */
	onConfirm: (channel: SlackMonitoredChannel) => Promise<void> | void;
}

/**
 * Deliberate affirmative-consent step (a Dialog, not a Switch): activation posts a public
 * announcement and begins reading new messages, so the three consequences are enumerated
 * before the admin confirms.
 */
export function ActivateChannelDialog({
	channel,
	onOpenChange,
	onConfirm,
}: ActivateChannelDialogProps) {
	const [submitting, setSubmitting] = useState(false);
	const label = channel ? (channel.channelName ?? channel.slackChannelId) : "";

	async function confirm() {
		if (!channel) return;
		setSubmitting(true);
		try {
			await onConfirm(channel);
			onOpenChange(false);
		} catch {
			// Rejection = keep the dialog open. The mutation's onError already surfaced the
			// toast, so swallow here rather than let it escape as an unhandled rejection.
		} finally {
			setSubmitting(false);
		}
	}

	return (
		<Dialog open={channel != null} onOpenChange={onOpenChange}>
			<DialogContent>
				<DialogHeader>
					<DialogTitle className="flex items-center gap-2">
						<MegaphoneIcon className="size-4" aria-hidden />
						Activate monitoring for #{label}?
					</DialogTitle>
					<DialogDescription>Activating #{label} will:</DialogDescription>
				</DialogHeader>

				<ul className="list-disc space-y-1.5 pl-5 text-sm text-muted-foreground">
					<li>
						<strong>Post a visible announcement</strong> in the channel so everyone knows AI
						mentoring is on.
					</li>
					<li>
						<strong>Begin reading new messages</strong> from now on (never past history —
						forward-only).
					</li>
					<li>
						Let any member <strong>opt out</strong> individually from the app's Home tab.
					</li>
				</ul>

				<DialogFooter>
					<DialogClose render={<Button variant="outline" disabled={submitting} />}>
						Cancel
					</DialogClose>
					<Button onClick={() => void confirm()} disabled={submitting}>
						{submitting ? "Activating…" : "Activate monitoring"}
					</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}
