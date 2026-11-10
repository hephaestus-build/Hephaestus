import { Link } from "@tanstack/react-router";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";

import {
	ConfirmAccessDialog,
	type ConfirmAccessDialogProps,
} from "@/components/auth/ConfirmAccessDialog";

import { DeleteWorkspaceAlertDialog } from "./DeleteWorkspaceAlertDialog";

/** Whether the reader owns the workspace, which deletion needs. */
export type OwnerCheck =
	| { status: "pending" }
	| { status: "failed"; retrying: boolean; onRetry: () => void }
	| { status: "known"; owner: boolean };

export interface WorkspaceDangerZoneSettingsProps {
	workspaceSlug: string;
	ownerCheck: OwnerCheck;
	deleting: boolean;
	/** Deletes the workspace. Resolves on success; rejects to keep the confirmation open. */
	onDelete: () => Promise<void>;
	/** Shown when the server asks for a recent sign-in before it deletes the workspace. */
	confirmation: ConfirmAccessDialogProps;
}

export function WorkspaceDangerZoneSettings({
	workspaceSlug,
	ownerCheck,
	deleting,
	onDelete,
	confirmation,
}: WorkspaceDangerZoneSettingsProps) {
	const [dialogOpen, setDialogOpen] = useState(false);
	const isOwner = ownerCheck.status === "known" && ownerCheck.owner;

	return (
		<section aria-labelledby="workspace-danger-zone-heading">
			<h2 id="workspace-danger-zone-heading" className="mb-4 text-lg font-semibold">
				Danger zone
			</h2>
			<Card variant="destructive">
				<CardContent>
					<div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:gap-6">
						<div className="flex-1 space-y-2">
							<h3 className="text-base font-medium">Delete this workspace</h3>
							<p className="text-sm leading-relaxed text-muted-foreground">
								This permanently deletes workspace content, settings, memberships, and locally
								stored credentials. Audit and accounting records remain. You cannot undo this.
							</p>
							<p className="text-sm leading-relaxed text-muted-foreground">
								Hephaestus has no workspace-level export. The{" "}
								<Link to="/settings" className="underline underline-offset-4 hover:text-foreground">
									personal export in account settings
								</Link>{" "}
								covers your own account only.
							</p>
							{ownerCheck.status === "pending" && (
								<p className="text-sm text-muted-foreground" role="status">
									Checking your permissions…
								</p>
							)}
							{ownerCheck.status === "failed" && (
								<p className="text-sm text-destructive" role="alert">
									We could not verify that you are the workspace owner.
								</p>
							)}
							{ownerCheck.status === "known" && !ownerCheck.owner && (
								<p className="text-sm text-muted-foreground">
									Only the workspace owner can delete this workspace.
								</p>
							)}
						</div>
						{isOwner && (
							<Button
								variant="destructive"
								className="w-full shrink-0 sm:mt-1 sm:w-auto"
								onClick={() => setDialogOpen(true)}
							>
								Delete workspace
							</Button>
						)}
						{ownerCheck.status === "failed" && (
							<Button
								variant="outline"
								className="w-full shrink-0 sm:mt-1 sm:w-auto"
								disabled={ownerCheck.retrying}
								onClick={ownerCheck.onRetry}
							>
								{ownerCheck.retrying && <Spinner aria-hidden />}
								{ownerCheck.retrying ? "Retrying…" : "Retry"}
							</Button>
						)}
					</div>
				</CardContent>
			</Card>

			<DeleteWorkspaceAlertDialog
				key={workspaceSlug}
				// The sign-in request replaces the confirmation while it is open.
				open={dialogOpen && !confirmation.open}
				onOpenChange={setDialogOpen}
				workspaceSlug={workspaceSlug}
				isDeleting={deleting}
				onConfirm={() => {
					onDelete()
						.then(() => setDialogOpen(false))
						// The failure has its own notice, and the open confirmation lets the owner try again.
						.catch(() => undefined);
				}}
			/>
			<ConfirmAccessDialog {...confirmation} />
		</section>
	);
}
