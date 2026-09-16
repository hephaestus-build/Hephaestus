import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { Building2 } from "lucide-react";
import { useDeferredValue, useState } from "react";
import { z } from "zod";
import { withSessionMutationLock } from "@/runtime/auth/session-mutation";

import { adminListWorkspacesOptions, impersonateMutation } from "@/api/@tanstack/react-query.gen";
import type { AdminWorkspaceView } from "@/api/types.gen";
import { ImpersonateDialog } from "@/components/admin/users/ImpersonateDialog";
import { AdminWorkspacesTable } from "@/components/admin/workspaces/AdminWorkspacesTable";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Label } from "@/components/ui/label";
import { instanceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/admin/workspaces")({
	head: instanceAdminHead("Workspaces"),
	validateSearch: z.object({ q: z.string().max(200).optional().catch(undefined) }),
	component: AdminWorkspacesPage,
});

function AdminWorkspacesPage() {
	const navigate = useNavigate({ from: Route.fullPath });
	const search = Route.useSearch().q ?? "";
	const deferredSearch = useDeferredValue(search);
	const [impersonateTarget, setImpersonateTarget] = useState<AdminWorkspaceView | null>(null);

	const listQuery = useQuery(adminListWorkspacesOptions());
	const impersonate = useMutation(withSessionMutationLock(impersonateMutation()));
	const all: AdminWorkspaceView[] = listQuery.data ?? [];

	const term = deferredSearch.trim().toLowerCase();
	const workspaces = term
		? all.filter((ws) =>
				[
					ws.displayName,
					ws.workspaceSlug,
					ws.accountLogin,
					ws.ownerLogin,
					ws.status,
					ws.providerType,
				]
					.filter(Boolean)
					.some((field) => field?.toLowerCase().includes(term) === true),
			)
		: all;

	return (
		<PageLayout>
			<PageHeader
				icon={<Building2 />}
				title="Workspaces"
				description="View every workspace on this instance and its ownership and status."
			/>

			<InputGroup className="w-full sm:max-w-sm">
				<Label htmlFor="admin-workspaces-search" className="sr-only">
					Search workspaces
				</Label>
				<InputGroupAddon>
					<Building2 aria-hidden />
				</InputGroupAddon>
				<InputGroupInput
					id="admin-workspaces-search"
					type="search"
					placeholder="Search by name, slug, owner, provider, or status…"
					value={search}
					onChange={(event) => {
						void navigate({
							search: { q: event.target.value || undefined },
							replace: true,
						});
					}}
				/>
			</InputGroup>

			<AdminWorkspacesTable
				workspaces={workspaces}
				isLoading={listQuery.isLoading}
				isError={listQuery.isError}
				hasSearch={term.length > 0}
				onImpersonateOwner={(workspace) => {
					impersonate.reset();
					setImpersonateTarget(workspace);
				}}
			/>

			<ImpersonateDialog
				user={
					impersonateTarget?.ownerAccountId == null
						? null
						: {
								id: impersonateTarget.ownerAccountId,
								displayName: impersonateTarget.ownerLogin ?? impersonateTarget.displayName,
							}
				}
				defaultReason={
					impersonateTarget
						? `Viewing workspace ${impersonateTarget.workspaceSlug} as its owner`
						: undefined
				}
				isPending={impersonate.isPending}
				errorMessage={
					impersonate.isError
						? problemDetailOf(impersonate.error, "Couldn't start impersonation.")
						: undefined
				}
				onOpenChange={(open) => {
					if (!open) {
						setImpersonateTarget(null);
						impersonate.reset();
					}
				}}
				onConfirm={(user, reason) => {
					if (user.id == null || impersonateTarget == null) {
						return;
					}
					const { workspaceSlug } = impersonateTarget;
					impersonate.mutate(
						{ body: { targetAccountId: user.id, reason } },
						{
							onSuccess: () => {
								setImpersonateTarget(null);
								window.location.assign(`/w/${encodeURIComponent(workspaceSlug)}`);
							},
						},
					);
				}}
			/>
		</PageLayout>
	);
}
