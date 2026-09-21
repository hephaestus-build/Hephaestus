import { type SubmitEvent, useRef, useState } from "react";

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
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";

export interface DeleteWorkspaceAlertDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	workspaceSlug: string;
	isDeleting: boolean;
	onConfirm: () => void;
}

export function DeleteWorkspaceAlertDialog({
	open,
	onOpenChange,
	workspaceSlug,
	isDeleting,
	onConfirm,
}: DeleteWorkspaceAlertDialogProps) {
	const [confirmText, setConfirmText] = useState("");
	const [mismatch, setMismatch] = useState(false);
	const titleRef = useRef<HTMLHeadingElement>(null);

	function handleOpenChange(next: boolean) {
		if (isDeleting && !next) {
			return;
		}
		if (!next) {
			setConfirmText("");
			setMismatch(false);
		}
		onOpenChange(next);
	}

	function confirm(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (confirmText !== workspaceSlug) {
			setMismatch(true);
			return;
		}
		onConfirm();
	}

	return (
		<AlertDialog open={open} onOpenChange={handleOpenChange}>
			<AlertDialogContent initialFocus={titleRef}>
				<AlertDialogHeader>
					<AlertDialogTitle ref={titleRef} tabIndex={-1}>
						Permanently delete <span className="font-mono break-all">{workspaceSlug}</span>?
					</AlertDialogTitle>
					<AlertDialogDescription>This action cannot be undone.</AlertDialogDescription>
					<div className="space-y-3 text-left text-sm text-muted-foreground">
						<p>Deleting the workspace permanently erases:</p>
						<ul className="list-disc space-y-1 pl-5">
							<li>memberships and access;</li>
							<li>workspace, team, and repository settings;</li>
							<li>Hephaestus copies of synced integration content;</li>
							<li>practice feedback and mentor conversations;</li>
							<li>locally stored integration and AI-provider credentials.</li>
						</ul>
						<p>These survive:</p>
						<ul className="list-disc space-y-1 pl-5">
							<li>
								messages and comments Hephaestus posted to external providers, including GitHub,
								GitLab, and Slack;
							</li>
							<li>
								GitHub, GitLab, and Outline access tokens at their providers; revoke them there if
								no longer needed;
							</li>
							<li>
								the Slack app installation; its bot token is revoked only when no other workspace
								uses it;
							</li>
							<li>security, audit, and accounting records for prior activity;</li>
							<li>
								the name <span className="font-mono break-all">{workspaceSlug}</span>, which stays
								reserved and can never be used for a new workspace.
							</li>
						</ul>
					</div>
				</AlertDialogHeader>

				<form onSubmit={confirm} className="grid gap-4">
					<Field data-invalid={mismatch}>
						<FieldLabel htmlFor="delete-workspace-confirm">
							Type <span className="font-mono font-medium break-all">{workspaceSlug}</span> to
							confirm
						</FieldLabel>
						<Input
							id="delete-workspace-confirm"
							value={confirmText}
							disabled={isDeleting}
							onChange={(e) => {
								setConfirmText(e.target.value);
								setMismatch(false);
							}}
							autoComplete="off"
							autoCapitalize="off"
							spellCheck={false}
							aria-invalid={mismatch}
							aria-describedby={mismatch ? "delete-workspace-confirm-error" : undefined}
						/>
						{mismatch && (
							<FieldError id="delete-workspace-confirm-error">
								That does not match. Type the workspace slug exactly.
							</FieldError>
						)}
					</Field>

					<AlertDialogFooter>
						<AlertDialogCancel disabled={isDeleting}>Keep workspace</AlertDialogCancel>
						<AlertDialogAction type="submit" variant="destructive" disabled={isDeleting}>
							{isDeleting && <Spinner aria-hidden />}
							{isDeleting ? "Deleting…" : "Delete workspace"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</form>
			</AlertDialogContent>
		</AlertDialog>
	);
}
