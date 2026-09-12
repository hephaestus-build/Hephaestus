import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { Building2 } from "lucide-react";
import { useDeferredValue } from "react";
import { z } from "zod";

import { adminListWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import type { AdminWorkspaceView } from "@/api/types.gen";
import { AdminWorkspacesTable } from "@/components/admin/workspaces/AdminWorkspacesTable";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { instanceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/admin/workspaces")({
	head: instanceAdminHead("Workspaces"),
	validateSearch: z.object({ q: z.string().max(200).optional().catch(undefined) }),
	component: AdminWorkspacesPage,
});

function AdminWorkspacesPage() {
	const navigate = useNavigate({ from: Route.fullPath });
	const search = Route.useSearch().q ?? "";
	const deferredSearch = useDeferredValue(search);

	const listQuery = useQuery(adminListWorkspacesOptions());
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
					.some((field) => field?.toLowerCase().includes(term)),
			)
		: all;

	return (
		<PageLayout>
			<PageHeader
				icon={<Building2 />}
				title="Workspaces"
				description="View every workspace on this instance and its ownership and status."
			/>

			<div className="relative w-full sm:max-w-sm">
				<Label htmlFor="admin-workspaces-search" className="sr-only">
					Search workspaces
				</Label>
				<Building2 className="absolute left-3 top-2.5 size-4 text-muted-foreground" aria-hidden />
				<Input
					id="admin-workspaces-search"
					type="search"
					placeholder="Search by name, slug, owner, provider, or status…"
					value={search}
					onChange={(event) =>
						void navigate({
							search: { q: event.target.value || undefined },
							replace: true,
						})
					}
					className="pl-9"
				/>
			</div>

			<AdminWorkspacesTable
				workspaces={workspaces}
				isLoading={listQuery.isLoading}
				isError={listQuery.isError}
				hasSearch={term.length > 0}
				onViewUsers={(workspace) =>
					void navigate({
						to: "/admin/workspaces/$workspaceSlug/users",
						params: { workspaceSlug: workspace.workspaceSlug },
						search: {},
					})
				}
			/>
		</PageLayout>
	);
}
