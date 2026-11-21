import { Suspense } from "react";

import type { WorkspaceMoveToRepositoryDialogProps } from "@/components/admin/practices/WorkspaceMoveToRepositoryDialog";
import { lazyComponent, useLazyComponent } from "@/lib/lazy-component";

// A rare admin action, so its code loads when a practice's panel opens, not with Practice setup.
const moveToRepositoryDialog = lazyComponent<WorkspaceMoveToRepositoryDialogProps>(async () => {
	const dialog = await import("./WorkspaceMoveToRepositoryDialog");
	return dialog.WorkspaceMoveToRepositoryDialog;
});

/** Loads the dialog ahead of its first use, so it then renders in the same pass. */
export const loadMoveToRepositoryDialog = moveToRepositoryDialog.preload;

export function LazyMoveToRepositoryDialog(props: WorkspaceMoveToRepositoryDialogProps) {
	const Dialog = useLazyComponent(moveToRepositoryDialog);
	// Closed until an admin asks to move, which happens after the code has loaded.
	return (
		<Suspense fallback={null}>
			<Dialog {...props} />
		</Suspense>
	);
}
