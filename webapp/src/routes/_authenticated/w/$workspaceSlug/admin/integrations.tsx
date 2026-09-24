import { createFileRoute, Outlet } from "@tanstack/react-router";

import { SyncFreshnessBanner } from "@/components/admin/integrations/SyncFreshnessBanner";
import { useSyncEvents } from "@/hooks/use-sync-events";
import { SyncLivenessProvider } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations")({
	head: workspaceAdminHead("Integrations"),
	component: IntegrationsLayout,
});

function IntegrationsLayout() {
	const { workspaceSlug } = Route.useParams();
	const livePushUnavailable = useSyncEvents(workspaceSlug);

	return (
		<SyncLivenessProvider livePushUnavailable={livePushUnavailable}>
			<div className="space-y-6">
				<SyncFreshnessBanner />
				<Outlet />
			</div>
		</SyncLivenessProvider>
	);
}
