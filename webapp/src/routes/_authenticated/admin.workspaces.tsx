import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { Building2 } from "lucide-react";
import { useDeferredValue } from "react";
import { z } from "zod";

import { adminListWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import type { AdminWorkspaceView } from "@/api/types.gen";
import { AdminWorkspacesTable } from "@/components/admin/workspaces/AdminWorkspacesTable";
import { queryErrorIsRetryable } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Label } from "@/components/ui/label";
import { instanceAdminHead } from "@/lib/page-title";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute("/_authenticated/admin/workspaces")({
	head: instanceAdminHead("Workspaces"),
	validateSearch: z.object({ q: z.string().max(200).optional().catch(undefined) }),
	component: AdminWorkspacesPage,
});

function AdminWorkspacesPage() {
	const navigate = useNavigate({ from: Route.fullPath });
	const search = Route.useSearch().q ?? "";
	const deferredSearch = useDeferredValue(search);

	const listOptions = adminListWorkspacesOptions();
	const listQuery = useQuery(listOptions);
	useQueryReadRefusal(listOptions.queryKey, listQuery.isError, listQuery.error);
	// A retryable failed refresh keeps the workspaces this key last read, with the failure beside
	// them. A refusal, or a read that never succeeded, shows none.
	const retained = listQuery.isRefetchError && queryErrorIsRetryable(listQuery.error);
	const failed = listQuery.isError && !retained;
	const failure = {
		error: listQuery.error,
		pending: listQuery.isFetching,
		onRetry: () => {
			void listQuery.refetch();
		},
	};
	const all: AdminWorkspaceView[] = failed ? [] : (listQuery.data ?? []);

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
				description="View every workspace on this instance, with its owner and status."
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
				loadFailure={failed ? failure : undefined}
				refreshFailure={retained ? failure : undefined}
				hasSearch={term.length > 0}
				onViewAsUser={(workspace) => {
					void navigate({
						to: "/admin/workspaces/$workspaceSlug/users",
						params: { workspaceSlug: workspace.workspaceSlug },
						search: {},
					});
				}}
			/>
		</PageLayout>
	);
}
