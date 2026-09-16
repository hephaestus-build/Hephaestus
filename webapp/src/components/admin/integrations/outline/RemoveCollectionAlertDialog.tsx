import { useState } from "react";

import type { OutlineCollection } from "@/api/types.gen";
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

export interface RemoveCollectionAlertDialogProps {
	/** Non-null drives the open state and names the collection being erased. */
	collection: OutlineCollection | null;
	onOpenChange: (open: boolean) => void;
	/** Confirm removal. Resolves on success (dialog closes), rejects to keep it open. */
	onConfirm: (input: { collectionId: string }) => Promise<void> | void;
}

/**
 * Destructive confirmation for removing a mirrored collection. The copy states the real
 * consequence — every mirrored document is erased from Hephaestus — while making clear the
 * source documents in Outline are untouched (unlike Slack messages, the corpus is re-syncable,
 * so no type-to-confirm gate).
 */
export function RemoveCollectionAlertDialog({
	collection,
	onOpenChange,
	onConfirm,
}: RemoveCollectionAlertDialogProps) {
	const [submitting, setSubmitting] = useState(false);

	const label = collection ? (collection.name ?? collection.collectionId) : "";
	const documentCount = collection?.documentCount ?? 0;

	function handleOpenChange(next: boolean) {
		if (!next) {
			setSubmitting(false);
		}
		onOpenChange(next);
	}

	async function confirm() {
		if (!collection) {
			return;
		}
		setSubmitting(true);
		try {
			await onConfirm({ collectionId: collection.collectionId });
			handleOpenChange(false);
		} catch {
			// Rejection = keep the dialog open. The mutation's onError already surfaced the
			// toast, so swallow here rather than let it escape as an unhandled rejection.
		}
		setSubmitting(false);
	}

	return (
		<AlertDialog open={collection != null} onOpenChange={handleOpenChange}>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogTitle>Remove “{label}” and erase its mirror?</AlertDialogTitle>
					<AlertDialogDescription>
						This stops mirroring the collection and{" "}
						<strong>
							permanently erases{" "}
							{documentCount === 1
								? "its 1 mirrored document"
								: `all ${documentCount} mirrored documents`}{" "}
							from Hephaestus
						</strong>
						. The documents in Outline itself are not affected, and you can mirror the collection
						again later.
					</AlertDialogDescription>
				</AlertDialogHeader>
				<AlertDialogFooter>
					<AlertDialogCancel disabled={submitting}>Cancel</AlertDialogCancel>
					<AlertDialogAction
						variant="destructive"
						disabled={submitting}
						onClick={() => void confirm()}
					>
						{submitting ? "Removing…" : "Remove & erase"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
