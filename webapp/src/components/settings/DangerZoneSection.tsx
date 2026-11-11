import { useState } from "react";

import {
	ConfirmAccessDialog,
	type ConfirmAccessDialogProps,
} from "@/components/auth/ConfirmAccessDialog";
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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";

const DELETE_CONFIRM_PHRASE = "delete my account";

/** Where the reader's data export stands. `stalled`: the server still works on it after three minutes. */
export type DataExportState = "idle" | "requesting" | "preparing" | "ready" | "stalled" | "failed";

export interface DangerZoneSectionProps {
	dataExport: {
		state: DataExportState;
		downloading: boolean;
		onRequest: () => void;
		onDownload: () => void;
	};
	deletion: {
		/** The signed-in account is known, so the server can confirm whose account to delete. */
		available: boolean;
		deleting: boolean;
		onDelete: () => void;
		/** Shown when the server asks for a recent sign-in before it deletes the account. */
		confirmation: ConfirmAccessDialogProps;
	};
}

/**
 * Danger-zone settings: GDPR data export (Art. 20) and account deletion (Art. 17).
 */
export function DangerZoneSection({ dataExport, deletion }: DangerZoneSectionProps) {
	return (
		<section className="space-y-6" aria-labelledby="danger-zone-heading">
			<div className="space-y-1">
				<h2 id="danger-zone-heading" className="text-xl font-semibold">
					Danger zone
				</h2>
				<p className="text-sm text-muted-foreground">
					Export your data or permanently delete your account.
				</p>
			</div>

			<DataExportRow {...dataExport} />
			<DeleteAccountRow {...deletion} />
		</section>
	);
}

/** GDPR Art. 20 data export: request it, follow its preparation, then download it. */
function DataExportRow({
	state,
	downloading,
	onRequest,
	onDownload,
}: DangerZoneSectionProps["dataExport"]) {
	const isPreparing = state === "requesting" || state === "preparing";
	const isFailed = state === "failed" || state === "stalled";
	const statusText = {
		idle: "",
		requesting: "Requesting export…",
		preparing: "Preparing your export… This can take a moment.",
		ready: "Your export is ready to download.",
		stalled: "This is taking longer than expected. Try again.",
		failed: "We could not prepare your export. Try again.",
	}[state];

	return (
		<div className="flex items-start justify-between gap-6 py-2">
			<div className="flex-1 space-y-1">
				<h3 className="text-base font-medium">Export your data</h3>
				<p className="text-sm leading-relaxed text-muted-foreground">
					Download a copy of your personal data (GDPR Art. 20) as a JSON file.
				</p>
				{statusText && (
					<p className="text-sm text-muted-foreground" aria-live="polite">
						{statusText}
					</p>
				)}
			</div>
			<div className="mt-1 flex shrink-0 gap-2">
				{state === "ready" ? (
					<Button variant="outline" onClick={onDownload} disabled={downloading}>
						{downloading ? <Spinner className="mr-1.5" /> : null}
						Download
					</Button>
				) : (
					<Button variant="outline" onClick={onRequest} disabled={isPreparing}>
						{isPreparing ? <Spinner className="mr-1.5" /> : null}
						{isFailed ? "Request export again" : "Request export"}
					</Button>
				)}
			</div>
		</div>
	);
}

/**
 * GDPR Art. 17 account deletion. Requires typing a confirmation phrase before the
 * destructive action enables.
 */
function DeleteAccountRow({
	available,
	deleting,
	onDelete,
	confirmation,
}: DangerZoneSectionProps["deletion"]) {
	const [open, setOpen] = useState(false);
	const [confirmText, setConfirmText] = useState("");
	const confirmed = confirmText.trim().toLowerCase() === DELETE_CONFIRM_PHRASE;

	const handleConfirm = () => {
		if (confirmed && available) {
			onDelete();
		}
	};

	return (
		<div className="flex items-start justify-between gap-6 py-2">
			<div className="flex-1 space-y-1">
				<h3 className="text-base font-medium">Delete account</h3>
				<p className="text-sm leading-relaxed text-muted-foreground">
					Permanently delete your account and erase your personal data (GDPR Art. 17). Hephaestus
					signs you out on all devices and deletes the account at once. Nobody can recover it. If
					Hephaestus posted feedback for you on GitHub, GitLab, or Slack, erasure finishes after
					your instance operator removes those comments.
				</p>
			</div>
			<AlertDialog
				// The sign-in request replaces the confirmation while it is open.
				open={open && !confirmation.open}
				onOpenChange={(next) => {
					setOpen(next);
					if (!next) {
						setConfirmText("");
					}
				}}
			>
				<AlertDialogTrigger
					render={
						<Button variant="destructive" disabled={deleting} className="mt-1 shrink-0">
							{deleting ? "Deleting…" : "Delete account"}
						</Button>
					}
				/>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Delete your account?</AlertDialogTitle>
						<AlertDialogDescription>
							This signs you out on all devices and permanently deletes your account at once. Nobody
							can undo this. To confirm, enter{" "}
							<span className="font-medium text-foreground">{DELETE_CONFIRM_PHRASE}</span> below.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<div className="space-y-2">
						<Label htmlFor="delete-confirm">Confirmation phrase</Label>
						<Input
							id="delete-confirm"
							value={confirmText}
							onChange={(event) => setConfirmText(event.target.value)}
							placeholder={DELETE_CONFIRM_PHRASE}
							autoComplete="off"
							aria-describedby="delete-confirm-help"
						/>
						<p id="delete-confirm-help" className="text-xs text-muted-foreground">
							Enter the phrase exactly to enable deletion.
						</p>
					</div>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={handleConfirm}
							disabled={!confirmed || !available || deleting}
							variant="destructive"
						>
							{deleting ? "Deleting…" : "Delete account"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
			<ConfirmAccessDialog {...confirmation} />
		</div>
	);
}
