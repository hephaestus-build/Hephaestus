import { createFileRoute } from "@tanstack/react-router";

import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { OutlineCollectionsSection } from "@/components/admin/integrations/outline/OutlineCollectionsSection";
import { OutlineConnectCard } from "@/components/admin/integrations/outline/OutlineConnectCard";
import { SyncResourcesTable } from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
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
		head: workspaceAdminHead("Outline"),
		remountDeps: ({ params }) => params.workspaceSlug,
		component: OutlineIntegrationPage,
	},
);

function OutlineIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const outline = useOutlineIntegration(slug);
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
					title="We couldn't load the Outline connection"
					onRetry={outline.retryConnections}
				/>
			)}

			{!outline.isLoading && !outline.connectionsError && (
				<>
					{outline.tokenStatusError && (
						<QueryErrorAlert
							error={outline.tokenStatusError}
							title="We couldn't verify the Outline token"
							onRetry={outline.retryTokenStatus}
						/>
					)}

					{hasConnection && (
						<ConnectionStateNotice
							connectionState={outline.connectionState}
							credentialsUnreadableSince={outline.credentialsUnreadableSince}
							credentialRecovery="Replace it by disconnecting and connecting again"
							displayName="Outline"
						/>
					)}

					{hasConnection && <SyncStatusHeader label="Outline" {...outline.syncStatusHeaderProps} />}

					{hasConnection && (
						<Card>
							<CardHeader>
								<IntegrationCardHeading>Collection sync state</IntegrationCardHeading>
							</CardHeader>
							<CardContent>
								<SyncResourcesTable {...outline.syncResourcesProps} />
							</CardContent>
						</Card>
					)}

					<OutlineConnectCard key={connectionId ?? "new"} {...outline.connectCardProps} />

					{outline.collectionsProps && <OutlineCollectionsSection {...outline.collectionsProps} />}
				</>
			)}

			{hasConnection && <JobHistoryCard {...outline.jobHistoryProps} />}
		</PageLayout>
	);
}
