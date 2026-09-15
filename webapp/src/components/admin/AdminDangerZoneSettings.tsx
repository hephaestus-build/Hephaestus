import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";

import { listWorkspacesQueryKey, purgeWorkspaceMutation } from "@/api/@tanstack/react-query.gen";
import type { ListWorkspacesResponse } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { workspaceMembershipQueryOptions } from "@/integrations/auth/guard";
import { problemDetailOf } from "@/lib/problem-detail";

import { DeleteWorkspaceAlertDialog } from "./DeleteWorkspaceAlertDialog";

export interface AdminDangerZoneSettingsProps {
	workspaceSlug: string;
}

export function AdminDangerZoneSettings({ workspaceSlug }: AdminDangerZoneSettingsProps) {
	const queryClient = useQueryClient();
	const navigate = useNavigate();
	const [dialogOpen, setDialogOpen] = useState(false);

	const {
		data: membership,
		isPending: isRolePending,
		isError: isRoleError,
		isFetching: isRoleFetching,
		refetch: refetchRole,
	} = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const isOwner = membership?.role === "OWNER";
	const roleUnavailable = membership == null && isRoleError;

	const purgeWorkspace = useMutation({
		...purgeWorkspaceMutation(),
		onSuccess: () => {
			setDialogOpen(false);
			toast.success("Workspace deleted");
			queryClient.setQueryData(listWorkspacesQueryKey(), (workspaces?: ListWorkspacesResponse) =>
				workspaces?.filter((workspace) => workspace.workspaceSlug !== workspaceSlug),
			);
			void navigate({ to: "/", replace: true });
			void queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() });
		},
		onError: (e) => toast.error("Failed to delete workspace", { description: problemDetailOf(e) }),
	});

	return (
		<section aria-labelledby="workspace-danger-zone-heading">
			<h2 id="workspace-danger-zone-heading" className="text-lg font-semibold mb-4">
				Danger Zone
			</h2>
			<Card variant="destructive">
				<CardContent>
					<div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:gap-6">
						<div className="space-y-2 flex-1">
							<h3 className="text-base font-medium">Delete this workspace</h3>
							<p className="text-sm text-muted-foreground leading-relaxed">
								Permanently deletes workspace content, settings, memberships, and locally stored
								credentials. Audit and accounting records remain. This cannot be undone.
							</p>
							<p className="text-sm text-muted-foreground leading-relaxed">
								Hephaestus has no workspace-level export. The{" "}
								<Link to="/settings" className="underline underline-offset-4 hover:text-foreground">
									personal export in account settings
								</Link>{" "}
								covers your own account only.
							</p>
							{isRolePending && (
								<p className="text-sm text-muted-foreground" role="status">
									Checking your permissions…
								</p>
							)}
							{roleUnavailable && (
								<p className="text-sm text-destructive" role="alert">
									We couldn’t verify that you’re the workspace owner.
								</p>
							)}
							{!isRolePending && !roleUnavailable && !isOwner && (
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
						{roleUnavailable && (
							<Button
								variant="outline"
								className="w-full shrink-0 sm:mt-1 sm:w-auto"
								disabled={isRoleFetching}
								onClick={() => void refetchRole()}
							>
								{isRoleFetching && <Spinner aria-hidden />}
								{isRoleFetching ? "Retrying…" : "Retry"}
							</Button>
						)}
					</div>
				</CardContent>
			</Card>

			<DeleteWorkspaceAlertDialog
				key={workspaceSlug}
				open={dialogOpen}
				onOpenChange={setDialogOpen}
				workspaceSlug={workspaceSlug}
				isDeleting={purgeWorkspace.isPending}
				onConfirm={() => purgeWorkspace.mutate({ path: { workspaceSlug } })}
			/>
		</section>
	);
}
