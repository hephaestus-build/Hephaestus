import { useBlocker } from "@tanstack/react-router";
import { useState } from "react";

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

export interface UnsavedChangesGuard {
	/** Render this next to the form. It is inert until a navigation is actually blocked. */
	dialog: React.ReactNode;
	/**
	 * Hand this whatever the save dispatch returned: the guard stays down until the promise settles,
	 * so a post-save navigation is not blocked by its own success, and comes back up if it rejects.
	 * A no-op for a non-promise — an `onSubmit` returning `void` cannot report failure at all.
	 */
	track: (submission: unknown) => void;
}

export interface UseUnsavedChangesOptions {
	isDirty: boolean;
	/** Suppress the guard entirely — while a mutation is in flight, or on a read-only form. */
	disabled?: boolean;
	/** Overrides the copy where "page" is the wrong word for the surface. */
	description?: string;
}

/**
 * The latch is the subtle part: `isPending` drops before the caller navigates, so releasing on it
 * alone races the post-save navigation and asks the reader to discard work they just saved.
 *
 * Blocks *every* navigation, which is what makes Cancel work on a guarded drawer level — leaving
 * one is a navigation. See `webapp/AGENTS.md` § Guarded levels for the trap on the other side.
 */
export function useUnsavedChanges({
	isDirty,
	disabled = false,
	description = "Your draft will be lost if you leave.",
}: UseUnsavedChangesOptions): UnsavedChangesGuard {
	const [saving, setSaving] = useState(false);
	const guarded = isDirty && !saving;
	const blocker = useBlocker({
		shouldBlockFn: () => guarded,
		enableBeforeUnload: guarded,
		disabled: !guarded || disabled,
		withResolver: true,
	});

	return {
		track: (submission) => {
			if (!(submission instanceof Promise)) {
				return;
			}
			setSaving(true);
			submission.catch(() => setSaving(false));
		},
		dialog: (
			<AlertDialog
				open={blocker.status === "blocked"}
				onOpenChange={(open, eventDetails) => {
					if (!open && eventDetails.reason === "escape-key") {
						blocker.reset?.();
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Discard unsaved changes?</AlertDialogTitle>
						<AlertDialogDescription>{description}</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel onClick={blocker.reset}>Keep editing</AlertDialogCancel>
						<AlertDialogAction variant="destructive" onClick={blocker.proceed}>
							Discard changes
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		),
	};
}
