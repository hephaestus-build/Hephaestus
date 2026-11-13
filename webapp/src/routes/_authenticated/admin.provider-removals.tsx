import { createFileRoute } from "@tanstack/react-router";
import { ShieldAlertIcon } from "lucide-react";

import { ProviderResourceRepairs } from "@/components/admin/integrations/ProviderResourceRepairs";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { useInstanceProviderResourceRepairs } from "@/hooks/use-provider-resource-repairs";
import { instanceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/admin/provider-removals")({
	head: instanceAdminHead("Provider removals"),
	component: AdminProviderRemovalsPage,
});

function AdminProviderRemovalsPage() {
	const repairs = useInstanceProviderResourceRepairs();
	return (
		<PageLayout>
			<PageHeader
				icon={<ShieldAlertIcon />}
				title="Provider removals"
				description="Review unresolved removals, including resources whose connection no longer exists."
			/>
			<ProviderResourceRepairs {...repairs} />
		</PageLayout>
	);
}
