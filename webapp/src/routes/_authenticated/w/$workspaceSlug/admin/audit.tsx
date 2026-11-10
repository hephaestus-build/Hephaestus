import { createFileRoute } from "@tanstack/react-router";
import { ScrollTextIcon } from "lucide-react";

import {
	type ConfigAuditSearch,
	workspaceAuditSearchSchema,
} from "@/components/admin/audit/audit-search";
import { ConfigAuditPanel } from "@/components/admin/audit/ConfigAuditPanel";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { useWorkspaceConfigAuditRead } from "@/hooks/use-config-audit-read";
import { workspaceAdminHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/audit")({
	head: workspaceAdminHead("Audit log"),
	component: WorkspaceAuditPage,
	validateSearch: workspaceAuditSearchSchema,
});

function WorkspaceAuditPage() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const read = useWorkspaceConfigAuditRead(workspaceSlug, search);

	const patchSearch = (patch: Partial<ConfigAuditSearch>) => {
		void setSearch((prev) => ({ ...prev, ...patch }), { replace: true });
	};

	return (
		<PageLayout>
			<PageHeader
				icon={<ScrollTextIcon />}
				title="Audit log"
				description="A permanent record of who changed workspace settings."
			/>

			<ConfigAuditPanel search={search} onSearchChange={patchSearch} {...read} />
		</PageLayout>
	);
}
