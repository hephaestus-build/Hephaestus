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
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export interface LeavableWorkspace {
	slug: string;
	name: string;
}

export interface LeaveWorkspaceSectionProps {
	workspaces: LeavableWorkspace[];
	isLoading?: boolean;
	/** The list could not be read. */
	loadFailure?: { retrying: boolean; onRetry: () => void };
	/** The workspace whose leave is under way. */
	leavingSlug?: string;
	/** Why the last leave failed, in the server's words. */
	leaveError?: string;
	/** Leaves the workspace. Resolves on success; rejects to keep the confirmation open. */
	onLeave: (slug: string) => Promise<void>;
	/** Clears the last failure when the confirmation closes. */
	onDismissError: () => void;
}

/** The workspaces the reader belongs to, each with a way to leave it. */
export function LeaveWorkspaceSection({
	workspaces,
	isLoading = false,
	loadFailure,
	leavingSlug,
	leaveError,
	onLeave,
	onDismissError,
}: LeaveWorkspaceSectionProps) {
	const [confirming, setConfirming] = useState<LeavableWorkspace>();
	const leaving = confirming !== undefined && leavingSlug === confirming.slug;

	let body;
	if (loadFailure !== undefined) {
		body = (
			<div className="space-y-2">
				<p className="text-sm text-destructive" role="alert">
					We could not load your workspaces.
				</p>
				<Button
					variant="outline"
					size="sm"
					disabled={loadFailure.retrying}
					onClick={loadFailure.onRetry}
				>
					{loadFailure.retrying && <Spinner aria-hidden />}
					{loadFailure.retrying ? "Retrying…" : "Retry"}
				</Button>
			</div>
		);
	} else if (isLoading) {
		body = <Skeleton className="h-10 w-full" />;
	} else if (workspaces.length === 0) {
		body = <p className="text-sm text-muted-foreground">You are not a member of a workspace.</p>;
	} else {
		body = (
			<ul className="divide-y rounded-lg border">
				{workspaces.map((workspace) => (
					<li key={workspace.slug} className="flex items-center justify-between gap-4 px-4 py-3">
						<span className="min-w-0 truncate text-sm font-medium">{workspace.name}</span>
						<Button
							variant="destructive-outline"
							size="sm"
							className="shrink-0"
							aria-label={`Leave ${workspace.name}`}
							disabled={leavingSlug === workspace.slug}
							onClick={() => setConfirming(workspace)}
						>
							Leave
						</Button>
					</li>
				))}
			</ul>
		);
	}

	return (
		<section className="space-y-4" aria-labelledby="leave-workspace-heading">
			<div className="space-y-1">
				<h2 id="leave-workspace-heading" className="text-xl font-semibold">
					Workspaces
				</h2>
				<p className="text-sm text-muted-foreground">
					Leave a workspace to lose access to it and stop getting its practice feedback. Sync with
					GitHub or GitLab does not bring you back. A workspace admin can add you back.
				</p>
			</div>
			{body}

			<AlertDialog
				open={confirming !== undefined}
				onOpenChange={(open) => {
					// A leave under way finishes in this confirmation, whose outcome it then shows.
					if (!open && !leaving) {
						setConfirming(undefined);
						onDismissError();
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Leave {confirming?.name}?</AlertDialogTitle>
						<AlertDialogDescription>
							You lose access to the workspace and stop getting its practice feedback. Your history
							there stays recorded. To come back, ask a workspace admin to add you back.
						</AlertDialogDescription>
					</AlertDialogHeader>
					{leaveError !== undefined && (
						<p className="text-sm text-destructive" role="alert">
							{leaveError}
						</p>
					)}
					<AlertDialogFooter>
						<AlertDialogCancel disabled={leaving}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={leaving}
							onClick={() => {
								if (confirming === undefined) {
									return;
								}
								onLeave(confirming.slug)
									.then(() => setConfirming(undefined))
									// The failure shows in the open confirmation, so the reader can read it or try again.
									.catch(() => undefined);
							}}
						>
							{leaving && <Spinner aria-hidden />}
							{leaving ? "Leaving…" : "Leave workspace"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</section>
	);
}
