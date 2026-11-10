import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "@/lib/toast";

import {
	getDataExportStatusOptions,
	requestDataExportMutation,
} from "@/api/@tanstack/react-query.gen";
import { deleteCurrentUser, downloadDataExport } from "@/api/sdk.gen";
import type {
	DangerZoneSectionProps,
	DataExportState,
} from "@/components/settings/DangerZoneSection";
import { saveBlobFile } from "@/lib/download";
import { hasText } from "@/lib/text";
import { recordAccountDeleted } from "@/runtime/auth/account-deleted-notice";
import { useAuth } from "@/runtime/auth/AuthContext";

// Export states that mean the server is still working — keep polling while in these.
const EXPORT_IN_PROGRESS = new Set(["PENDING", "PROCESSING"]);

// Give up polling after this long so a wedged export doesn't poll indefinitely.
const MAX_EXPORT_WAIT_MS = 3 * 60 * 1000;

/** The reader's data export and account deletion, for the Danger zone of their settings. */
export function useAccountDangerZone({
	onAccountDeleted,
}: {
	/** Called after the server confirms the deletion (e.g. open the sign-in page). */
	onAccountDeleted: () => void | Promise<void>;
}): DangerZoneSectionProps {
	return { dataExport: useDataExport(), deletion: useAccountDeletion(onAccountDeleted) };
}

/**
 * GDPR Art. 20 data export. Requests an export, polls its status while the server prepares it,
 * then saves the download as a JSON Blob.
 */
function useDataExport(): DangerZoneSectionProps["dataExport"] {
	const [exportId, setExportId] = useState<number | null>(null);
	const [requestedAt, setRequestedAt] = useState<number | null>(null);
	const [downloading, setDownloading] = useState(false);

	const requestExport = useMutation({
		...requestDataExportMutation(),
		onSuccess: (data) => {
			if (typeof data.id === "number") {
				setExportId(data.id);
				// oxlint-disable-next-line no-restricted-properties -- Read once on an event, and necessarily the same clock TanStack Query stamps `dataUpdatedAt` with: the stall check below subtracts one from the other.
				setRequestedAt(Date.now());
			} else {
				toast.error("We could not start your data export. Try again.");
			}
		},
		onError: () => {
			toast.error("We could not request your data export. Try again.");
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
	const stalled =
		inProgress &&
		requestedAt !== null &&
		statusQuery.dataUpdatedAt - requestedAt >= MAX_EXPORT_WAIT_MS;

	let state: DataExportState = "idle";
	if (requestExport.isPending) {
		state = "requesting";
	} else if (stalled) {
		state = "stalled";
	} else if (inProgress) {
		state = "preparing";
	} else if (status === "READY") {
		state = "ready";
	} else if (status === "FAILED" || status === "EXPIRED") {
		state = "failed";
	}

	const download = async () => {
		if (exportId === null) {
			return;
		}
		setDownloading(true);
		try {
			const response = await downloadDataExport({ path: { id: exportId }, parseAs: "blob" });
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
			saveBlobFile(blob, "hephaestus-export.json");
		} catch {
			toast.error("We could not download your export. Try again.");
		}
		setDownloading(false);
	};

	return {
		state,
		downloading,
		onRequest: () => requestExport.mutate({}),
		onDownload: () => {
			void download();
		},
	};
}

// The server requires the confirmation header to equal the caller's own account id
// (a deliberate "you know who you are" guard against forged/CSRF-style deletes).
// The sign-in page notice is recorded only once the server confirms the deletion.
async function deleteOwnAccount(accountId: string): Promise<void> {
	await deleteCurrentUser({ headers: { "X-Confirm-Delete": accountId }, throwOnError: true });
	recordAccountDeleted(accountId);
}

/** GDPR Art. 17 account deletion of the signed-in account. */
function useAccountDeletion(
	onAccountDeleted: () => void | Promise<void>,
): DangerZoneSectionProps["deletion"] {
	const { getUserId } = useAuth();
	const accountId = getUserId();
	const deleteAccount = useMutation({
		mutationFn: deleteOwnAccount,
		onSuccess: async () => {
			await onAccountDeleted();
		},
		onError: () => {
			toast.error("We could not delete your account. Try again.");
		},
	});
	return {
		available: hasText(accountId),
		deleting: deleteAccount.isPending,
		onDelete: () => {
			if (hasText(accountId)) {
				deleteAccount.mutate(accountId);
			}
		},
	};
}
