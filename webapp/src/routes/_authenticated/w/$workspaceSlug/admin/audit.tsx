import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ScrollTextIcon } from "lucide-react";

import {
	type ConfigAuditSearch,
	workspaceAuditSearchSchema,
} from "@/components/admin/audit/audit-search";
import { WorkspaceConfigAuditPanel } from "@/components/admin/audit/ConfigAuditPanel";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/audit")({
	head: workspaceAdminHead("Audit log"),
	component: WorkspaceAuditPage,
	validateSearch: workspaceAuditSearchSchema,
});

function WorkspaceAuditPage() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });

	const patchSearch = (patch: Partial<ConfigAuditSearch>) => {
		void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true });
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<ScrollTextIcon />}
				title="Audit log"
				description="A permanent record of who changed workspace settings."
			/>

			<WorkspaceConfigAuditPanel
				search={search}
				onSearchChange={patchSearch}
				workspaceSlug={workspaceSlug}
			/>
		</PageLayout>
	);
}
