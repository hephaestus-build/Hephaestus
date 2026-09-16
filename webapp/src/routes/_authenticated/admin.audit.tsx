import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ScrollTextIcon } from "lucide-react";

import { adminListWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { type AuditSearch, auditSearchSchema } from "@/components/admin/audit/audit-search";
import { AuthAuditPanel } from "@/components/admin/audit/AuthAuditPanel";
import { AdminConfigAuditPanel } from "@/components/admin/audit/ConfigAuditPanel";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { instanceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/admin/audit")({
	head: instanceAdminHead("Audit log"),
	component: AdminAuditPage,
	validateSearch: auditSearchSchema,
});

function AdminAuditPage() {
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });

	const patchSearch = (patch: Partial<AuditSearch>) =>
		void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true });

	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const workspaceNames = new Map(
		(workspacesQuery.data ?? []).map((w) => [w.id, w.displayName || w.workspaceSlug] as const),
	);
	const resolveWorkspaceName = (id: number) => workspaceNames.get(id);

	return (
		<PageLayout>
			<PageHeader
				icon={<ScrollTextIcon />}
				title="Audit log"
				description="Review sign-ins, permission changes, and settings changes across this instance."
			/>

			<Tabs
				className="gap-4"
				value={search.tab}
				onValueChange={(value) =>
					void navigate({
						search: (prev) => ({
							// The tab component hands back an untyped value; the schema's `.catch` decides what
							// a stray one becomes.
							tab: auditSearchSchema.shape.tab.parse(value),
							actorId: prev.actorId,
							from: prev.from,
							to: prev.to,
						}),
					})
				}
			>
				<TabsList className="h-10 w-full p-1 sm:w-fit">
					<TabsTrigger value="signins">Access</TabsTrigger>
					<TabsTrigger value="settings">Settings</TabsTrigger>
				</TabsList>

				<TabsContent value="signins">
					<AuthAuditPanel
						search={search}
						onSearchChange={patchSearch}
						resolveWorkspaceName={resolveWorkspaceName}
					/>
				</TabsContent>

				<TabsContent value="settings">
					<AdminConfigAuditPanel
						search={search}
						onSearchChange={patchSearch}
						resolveWorkspaceName={resolveWorkspaceName}
					/>
				</TabsContent>
			</Tabs>
		</PageLayout>
	);
}
