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
import { Button } from "@/components/ui/button";
import { Card, CardDescription, CardFooter, CardHeader } from "@/components/ui/card";

import { WorkspaceIntegrationCardHeading } from "./WorkspaceIntegrationCardHeading";

interface WorkspaceGitLabDisconnectProps {
	/** Rejects when the disconnect fails; the caller says why. */
	onDisconnect: () => Promise<void>;
	isDisconnecting: boolean;
}

export function WorkspaceGitLabDisconnect({
	onDisconnect,
	isDisconnecting,
}: WorkspaceGitLabDisconnectProps) {
	const [open, setOpen] = useState(false);

	const confirm = async () => {
		try {
			await onDisconnect();
			setOpen(false);
		} catch {
			// The dialog stays open to try again; the caller has already said why it failed.
		}
	};

	return (
		<Card>
			<CardHeader>
				<WorkspaceIntegrationCardHeading>Disconnect GitLab</WorkspaceIntegrationCardHeading>
				<CardDescription>
					Stop syncing this workspace with GitLab and erase the work that Hephaestus synced from it.
					The workspace and its members stay. To use a new token instead, replace the token above.
				</CardDescription>
			</CardHeader>
			<CardFooter className="justify-end">
				<Button
					variant="destructive-outline"
					onClick={() => setOpen(true)}
					disabled={isDisconnecting}
				>
					{isDisconnecting ? "Disconnecting…" : "Disconnect GitLab…"}
				</Button>
			</CardFooter>

			<AlertDialog open={open} onOpenChange={setOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Disconnect GitLab?</AlertDialogTitle>
						<AlertDialogDescription>
							This removes the GitLab connection for this workspace and asks GitLab to remove the
							webhooks that Hephaestus set up. If GitLab does not confirm, this page shows it. It
							also erases the repository mirror, the work synced from it, and the teams and activity
							read from GitLab. The practice results and feedback that came from that work are
							erased too. You cannot undo this. Projects in GitLab stay, and so does feedback that
							Hephaestus already posted on merge requests. To use GitLab later, reconnect it with a
							token. Hephaestus then syncs the work again. Revoke the old token in GitLab yourself.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={isDisconnecting}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={isDisconnecting}
							onClick={() => {
								void confirm();
							}}
						>
							{isDisconnecting ? "Disconnecting…" : "Disconnect GitLab"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</Card>
	);
}
