import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { WorkspaceProviderRepairs } from "@/routes/_authenticated/-provider-repairs";

import { OutlineCollectionsSection } from "@/components/admin/integrations/outline/OutlineCollectionsSection";
import { OutlineConnectCard } from "@/components/admin/integrations/outline/OutlineConnectCard";
import { WorkspaceConnectionStateNotice } from "@/components/admin/integrations/WorkspaceConnectionStateNotice";
import { WorkspaceIntegrationCardHeading } from "@/components/admin/integrations/WorkspaceIntegrationCardHeading";
import { WorkspaceJobHistoryCard } from "@/components/admin/integrations/WorkspaceJobHistoryCard";
import { WorkspaceSyncResourcesTable } from "@/components/admin/integrations/WorkspaceSyncResourcesTable";
import { WorkspaceSyncStatusHeader } from "@/components/admin/integrations/WorkspaceSyncStatusHeader";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { OutlineIcon } from "@/components/icons/brand";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useOutlineIntegration } from "@/hooks/use-outline-integration";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/outline")(
	{
		validateSearch: z.object({
			repairConnection: z.number().int().positive().optional().catch(undefined),
		}),
		head: workspaceAdminHead("Outline"),
		remountDeps: ({ params }) => params.workspaceSlug,
		component: OutlineIntegrationPage,
	},
);

function OutlineIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const outline = useOutlineIntegration(slug);
	const repairId = Route.useSearch().repairConnection ?? outline.repairConnectionId;
	const { connectionId, hasConnection } = outline;

	return (
		<PageLayout>
			<PageHeader
				icon={<OutlineIcon className="size-6" />}
				title="Outline"
				description="Mirror Outline collections so their documents reach practice reviews as context."
			/>

			{outline.isLoading && <Skeleton className="h-48 w-full" />}

			{outline.connectionsError && (
				<QueryErrorAlert
					error={outline.connectionsError}
					title="We could not load the Outline connection"
					onRetry={outline.retryConnections}
				/>
			)}

			{!outline.isLoading && !outline.connectionsError && (
				<>
					{outline.tokenStatusError && (
						<QueryErrorAlert
							error={outline.tokenStatusError}
							title="We could not verify the Outline token"
							onRetry={outline.retryTokenStatus}
						/>
					)}

					{hasConnection && (
						<WorkspaceConnectionStateNotice
							connectionState={outline.connectionState}
							credentialsUnreadableSince={outline.credentialsUnreadableSince}
							providerCleanupUnconfirmed={outline.providerCleanupUnconfirmed}
							credentialRecovery="Replace it by disconnecting and connecting again"
							displayName="Outline"
						/>
					)}

					{hasConnection && (
						<WorkspaceSyncStatusHeader label="Outline" {...outline.syncStatusHeaderProps} />
					)}

					{hasConnection && (
						<Card>
							<CardHeader>
								<WorkspaceIntegrationCardHeading>
									Collection sync state
								</WorkspaceIntegrationCardHeading>
							</CardHeader>
							<CardContent>
								<WorkspaceSyncResourcesTable {...outline.syncResourcesProps} />
							</CardContent>
						</Card>
					)}

					<OutlineConnectCard key={connectionId ?? "new"} {...outline.connectCardProps} />

					{outline.collectionsProps && <OutlineCollectionsSection {...outline.collectionsProps} />}
				</>
			)}

			{hasConnection && <WorkspaceJobHistoryCard {...outline.jobHistoryProps} />}
			{typeof repairId === "number" && (
				<WorkspaceProviderRepairs
					key={`${slug}/${repairId}`}
					workspaceSlug={slug}
					connectionId={repairId}
				/>
			)}
		</PageLayout>
	);
}
