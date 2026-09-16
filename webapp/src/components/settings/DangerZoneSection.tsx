import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	deleteCurrentUserMutation,
	getDataExportStatusOptions,
	requestDataExportMutation,
} from "@/api/@tanstack/react-query.gen";
import { downloadDataExport } from "@/api/sdk.gen";
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
import { hasText } from "@/lib/text";
import { ACCOUNT_DELETED_NOTICE_KEY } from "@/runtime/auth/account-deleted-notice";
import { useAuth } from "@/runtime/auth/AuthContext";

const DELETE_CONFIRM_PHRASE = "delete my account";

// Export states that mean the server is still working — keep polling while in these.
const EXPORT_IN_PROGRESS = new Set(["PENDING", "PROCESSING"]);

// Give up polling after this long so a wedged export doesn't poll indefinitely.
const MAX_EXPORT_WAIT_MS = 3 * 60 * 1000;

interface DangerZoneSectionProps {
	/** Called after the account is deleted (e.g. logout + redirect to /login). */
	onAccountDeleted: () => void | Promise<void>;
}

/**
 * Danger-zone settings: GDPR data export (Art. 20) and account deletion (Art. 17).
 */
export function DangerZoneSection({ onAccountDeleted }: DangerZoneSectionProps) {
	return (
		<section className="space-y-6" aria-labelledby="danger-zone-heading">
			<div className="space-y-1">
				<h2 id="danger-zone-heading" className="text-xl font-semibold">
					Danger Zone
				</h2>
				<p className="text-sm text-muted-foreground">
					Export your data or permanently delete your account.
				</p>
			</div>

			<DataExportRow />
			<DeleteAccountRow onAccountDeleted={onAccountDeleted} />
		</section>
	);
}

/**
 * GDPR Art. 20 data export. Requests an export, polls its status while the server
 * prepares it, then offers a download that is saved as a JSON Blob.
 */
function DataExportRow() {
	const [exportId, setExportId] = useState<number | null>(null);
	const [requestedAt, setRequestedAt] = useState<number | null>(null);
	const [isDownloading, setIsDownloading] = useState(false);

	const requestExport = useMutation({
		...requestDataExportMutation(),
		onSuccess: (data) => {
			if (typeof data.id === "number") {
				setExportId(data.id);
				// oxlint-disable-next-line no-restricted-properties -- Read once on an event, and necessarily the same clock TanStack Query stamps `dataUpdatedAt` with: the stall check below subtracts one from the other.
				setRequestedAt(Date.now());
			} else {
				toast.error("Export request did not return an identifier.");
			}
		},
		onError: () => {
			toast.error("Failed to request data export. Please try again later.");
		},
	});

	const statusQuery = useQuery({
		...getDataExportStatusOptions({ path: { id: exportId ?? 0 } }),
		enabled: exportId !== null,
		refetchInterval: (query) => {
			const status = query.state.data?.status?.toUpperCase();
			const stillWorking = hasText(status) && EXPORT_IN_PROGRESS.has(status);
			if (!stillWorking || requestedAt === null) {
				return stillWorking ? 2000 : false;
			}
			return query.state.dataUpdatedAt - requestedAt < MAX_EXPORT_WAIT_MS ? 2000 : false;
		},
	});

	const status = statusQuery.data?.status?.toUpperCase();
	const inProgress = exportId !== null && hasText(status) && EXPORT_IN_PROGRESS.has(status);
	// Polling gave up while the export was still working — let the user retry rather than spin forever.
	const isStalled =
		inProgress &&
		requestedAt !== null &&
		statusQuery.dataUpdatedAt - requestedAt >= MAX_EXPORT_WAIT_MS;
	const isPreparing = !isStalled && (requestExport.isPending || inProgress);
	const isReady = status === "READY";
	const isFailed = status === "FAILED" || status === "EXPIRED" || isStalled;

	const handleDownload = async () => {
		if (exportId === null) {
			return;
		}
		setIsDownloading(true);
		try {
			const response = await downloadDataExport({
				path: { id: exportId },
				parseAs: "blob",
			});
			// parseAs: "blob" yields a Blob at runtime even though the generated
			// response type is `string`; normalise defensively for other shapes.
			const payload: unknown = response.data;
			let blob: Blob;
			if (payload instanceof Blob) {
				blob = payload;
			} else if (typeof payload === "string") {
				blob = new Blob([payload], { type: "application/json" });
			} else {
				blob = new Blob([JSON.stringify(payload)], { type: "application/json" });
			}
			const url = URL.createObjectURL(blob);
			const anchor = document.createElement("a");
			anchor.href = url;
			anchor.download = "hephaestus-export.json";
			document.body.append(anchor);
			anchor.click();
			anchor.remove();
			URL.revokeObjectURL(url);
		} catch {
			toast.error("Failed to download export. Please try again later.");
		}
		setIsDownloading(false);
	};

	let statusText = "";
	if (requestExport.isPending) {
		statusText = "Requesting export…";
	} else if (isPreparing) {
		statusText = "Preparing your export… this can take a moment.";
	} else if (isReady) {
		statusText = "Your export is ready to download.";
	} else if (isStalled) {
		statusText = "This is taking longer than expected. Please try again.";
	} else if (isFailed) {
		statusText = "The export could not be prepared. Please try again.";
	}

	return (
		<div className="flex items-start justify-between gap-6 py-2">
			<div className="flex-1 space-y-1">
				<h3 className="text-base font-medium">Export my data</h3>
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
				{isReady ? (
					<Button variant="outline" onClick={() => void handleDownload()} disabled={isDownloading}>
						{isDownloading ? <Spinner className="mr-1.5" /> : null}
						Download
					</Button>
				) : (
					<Button variant="outline" onClick={() => requestExport.mutate({})} disabled={isPreparing}>
						{isPreparing ? <Spinner className="mr-1.5" /> : null}
						{isFailed ? "Retry export" : "Request export"}
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
function DeleteAccountRow({ onAccountDeleted }: DangerZoneSectionProps) {
	const { getUserId } = useAuth();
	const [confirmText, setConfirmText] = useState("");
	const confirmed = confirmText.trim().toLowerCase() === DELETE_CONFIRM_PHRASE;

	const deleteAccount = useMutation({
		...deleteCurrentUserMutation(),
		onSuccess: async () => {
			// Stash a one-shot flag so the login page (after logout's reload) can confirm the outcome —
			// a toast here would be destroyed by the reload onAccountDeleted triggers.
			try {
				sessionStorage.setItem(ACCOUNT_DELETED_NOTICE_KEY, "1");
			} catch {
				// best-effort; private mode may block sessionStorage
			}
			await onAccountDeleted();
		},
		onError: () => {
			toast.error("Failed to delete account. Please try again later.");
		},
	});

	const handleConfirm = () => {
		// The server requires the confirmation header to equal the caller's own account id
		// (a deliberate "you know who you are" guard against forged/CSRF-style deletes).
		const userId = getUserId();
		if (!confirmed || !hasText(userId)) {
			return;
		}
		deleteAccount.mutate({ headers: { "X-Confirm-Delete": userId } });
	};

	return (
		<div className="flex items-start justify-between gap-6 py-2">
			<div className="flex-1 space-y-1">
				<h3 className="text-base font-medium">Delete account</h3>
				<p className="text-sm leading-relaxed text-muted-foreground">
					Permanently delete your account and erase your personal data (GDPR Art. 17). You'll be
					signed out on all devices immediately, and the account is scheduled for permanent deletion
					after a ~48-hour cooldown. It can't be recovered from here.
				</p>
			</div>
			<AlertDialog onOpenChange={(open) => !open && setConfirmText("")}>
				<AlertDialogTrigger
					render={
						<Button
							variant="destructive"
							disabled={deleteAccount.isPending}
							className="mt-1 shrink-0"
						>
							{deleteAccount.isPending ? "Deleting…" : "Delete"}
						</Button>
					}
				/>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Delete your account?</AlertDialogTitle>
						<AlertDialogDescription>
							This signs you out on all devices immediately and disables your account, then
							permanently deletes it and your data after a ~48-hour cooldown. It can't be undone
							from here. To confirm, type{" "}
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
							Type the phrase exactly to enable deletion.
						</p>
					</div>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={handleConfirm}
							disabled={!confirmed || !hasText(getUserId()) || deleteAccount.isPending}
							variant="destructive"
						>
							{deleteAccount.isPending ? "Deleting…" : "Delete account"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</div>
	);
}
