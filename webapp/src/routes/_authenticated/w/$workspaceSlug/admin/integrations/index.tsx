import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { PlugZap } from "lucide-react";
import { toast } from "sonner";

import {
	getConnectionSyncStatusOptions,
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogOptions,
	triggerSyncJobMutation,
} from "@/api/@tanstack/react-query.gen";
import { IntegrationOverviewCard } from "@/components/admin/integrations/IntegrationOverviewCard";
import { syncPollInterval } from "@/components/admin/integrations/sync-format";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/")({
	head: workspaceAdminHead("Integrations"),
	// The cards are keyed by integration kind, which is the same key in every workspace, so a switch
	// would otherwise leave a sync this workspace never asked for pending on the matching card.
	remountDeps: ({ params }) => params.workspaceSlug,
	component: IntegrationsOverview,
});

function IntegrationsOverview() {
	const { workspaceSlug: slug } = Route.useParams();

	const catalogQuery = useQuery(getIntegrationCatalogOptions({ path: { workspaceSlug: slug } }));

	return (
		<PageLayout>
			<PageHeader
				icon={<PlugZap />}
				title="Integrations"
				description="Monitor connections, sync activity, and available integration controls."
			/>

			{catalogQuery.isLoading ? (
				<div className="grid items-stretch gap-4 sm:grid-cols-2">
					<Skeleton className="h-40 w-full" />
					<Skeleton className="h-40 w-full" />
				</div>
			) : catalogQuery.isError ? (
				<QueryErrorAlert
					error={catalogQuery.error}
					title="We couldn't load the integration catalog"
					onRetry={() => void catalogQuery.refetch()}
				/>
			) : (
				<div className="grid items-stretch gap-4 sm:grid-cols-2">
					{(catalogQuery.data ?? []).map((entry) => (
						<IntegrationOverviewCardContainer key={entry.kind} workspaceSlug={slug} entry={entry} />
					))}
				</div>
			)}
		</PageLayout>
	);
}

function IntegrationOverviewCardContainer({
	workspaceSlug,
	entry,
}: {
	workspaceSlug: string;
	entry: Parameters<typeof IntegrationOverviewCard>[0]["entry"];
}) {
	const queryClient = useQueryClient();
	const connectionId = entry.connectionId;
	const livePushUnavailable = useLivePushUnavailable();

	const statusQuery = useQuery({
		...getConnectionSyncStatusOptions({
			path: { workspaceSlug, connectionId: connectionId ?? -1 },
		}),
		enabled: entry.connected && connectionId != null,
		refetchInterval: (query) =>
			syncPollInterval(query.state.data?.activeJob != null, livePushUnavailable),
	});

	const triggerSync = useMutation({
		...triggerSyncJobMutation(),
		onSuccess: () => {
			if (connectionId == null) return;
			void queryClient.invalidateQueries({
				queryKey: getConnectionSyncStatusQueryKey({ path: { workspaceSlug, connectionId } }),
			});
			toast.success(`${entry.displayName} sync started`);
		},
		onError: (e) => {
			toast.error(`Failed to start sync for ${entry.displayName}`, {
				description: problemDetailOf(e),
			});
		},
	});

	return (
		<IntegrationOverviewCard
			workspaceSlug={workspaceSlug}
			entry={entry}
			status={statusQuery.data}
			isStatusLoading={statusQuery.isLoading}
			isStatusError={statusQuery.isError}
			statusError={statusQuery.error}
			onRetryStatus={() => void statusQuery.refetch()}
			isTriggering={triggerSync.isPending}
			onSync={() => {
				if (connectionId == null) return;
				triggerSync.mutate({
					path: { workspaceSlug, connectionId },
					body: { type: "RECONCILIATION" },
				});
			}}
		/>
	);
}
