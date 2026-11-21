import { CircleAlert } from "lucide-react";
import type { ComponentProps } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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

/** Edit in Hephaestus for one practice, as the route runs it. */
export interface EditInHephaestusControl {
	/** The practice repository's provider path, such as `acme/practices`. Absent while unknown. */
	repositoryName?: string;
	/** The reader asked to edit the practice in Hephaestus, and the request has not ended it. */
	confirming: boolean;
	/** The request, or the reload of the practice after it, is in flight. */
	pending: boolean;
	/** Why the last attempt failed, in the server's words. */
	error?: string;
	/** Opens the confirmation, or asks to close it; a close while pending is ignored. */
	onConfirmingChange: (confirming: boolean) => void;
	onConfirm: () => void;
}

export interface WorkspaceEditInHephaestusDialogProps {
	practiceName: string;
	/**
	 * Open while `control.confirming`. The request ends only once the practice has reloaded as
	 * editable, so the dialog closes in the same render that shows the control which edits it, and
	 * `finalFocus` finds that control.
	 */
	control: EditInHephaestusControl;
	/** Where focus goes once the dialog closes. */
	finalFocus: ComponentProps<typeof AlertDialogContent>["finalFocus"];
}

/**
 * Asks before a practice edited in the practice repository becomes editable in Hephaestus: later
 * changes to its folder stop reaching it, and Move to repository takes it back only from a valid
 * folder. It stays open until the practice has reloaded as editable, and keeps a refusal in view
 * rather than in a toast.
 */
export function WorkspaceEditInHephaestusDialog({
	practiceName,
	control,
	finalFocus,
}: WorkspaceEditInHephaestusDialogProps) {
	const { repositoryName, confirming, pending, error, onConfirmingChange, onConfirm } = control;
	const repository = repositoryName ?? "the practice repository";
	return (
		<AlertDialog
			open={confirming}
			onOpenChange={(next) => {
				// A dismissal while the request runs would hide whether it worked.
				if (!next && !pending) {
					onConfirmingChange(false);
				}
			}}
		>
			<AlertDialogContent finalFocus={finalFocus}>
				<AlertDialogHeader>
					<AlertDialogTitle>Edit “{practiceName}” in Hephaestus?</AlertDialogTitle>
					<AlertDialogDescription>
						You can then change it in Practice setup. It keeps its current version. Later changes to
						its folder in {repository} no longer reach the practice.
					</AlertDialogDescription>
				</AlertDialogHeader>
				{error !== undefined && (
					<Alert variant="destructive" role="alert">
						<CircleAlert />
						<AlertTitle>We could not make it editable</AlertTitle>
						<AlertDescription>{error}</AlertDescription>
					</Alert>
				)}
				<AlertDialogFooter>
					<AlertDialogCancel disabled={pending}>Cancel</AlertDialogCancel>
					<AlertDialogAction pending={pending} disabled={pending} onClick={onConfirm}>
						{pending ? "Making it editable…" : "Edit in Hephaestus"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
