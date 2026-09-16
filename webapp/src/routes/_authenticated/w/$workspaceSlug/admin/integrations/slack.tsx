import { createFileRoute } from "@tanstack/react-router";

import { AdminSlackChannelsSettings } from "@/components/admin/integrations/AdminSlackChannelsSettings";
import { AdminSlackNotificationSettings } from "@/components/admin/integrations/AdminSlackNotificationSettings";
import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { SyncResourcesTable } from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { SlackIcon } from "@/components/icons/brand";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useSlackIntegration } from "@/hooks/use-slack-integration";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/slack")({
	head: workspaceAdminHead("Slack"),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: SlackIntegrationPage,
});

function SlackIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const slack = useSlackIntegration(slug);
	const { hasConnection } = slack;
	const ready = !slack.isLoading && !slack.loadError;

	return (
		<PageLayout>
			<PageHeader
				icon={<SlackIcon className="size-6" />}
				title="Slack"
				description="Connection, weekly digest, monitored channels and sync activity for this workspace's Slack app."
			/>

			{slack.isLoading && <Skeleton className="h-48 w-full" />}

			{slack.loadError && (
				<QueryErrorAlert
					error={slack.loadError}
					title="We couldn't load the Slack connection"
					onRetry={slack.retryLoad}
				/>
			)}

			{ready && hasConnection && (
				<ConnectionStateNotice
					connectionState={slack.connectionState}
					credentialsUnreadableSince={slack.credentialsUnreadableSince}
					credentialRecovery="Replace it by disconnecting and connecting again"
					displayName="Slack"
				/>
			)}

			{ready && hasConnection && (
				<SyncStatusHeader label="Slack" {...slack.syncStatusHeaderProps} />
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<IntegrationCardHeading>Channel sync state</IntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<SyncResourcesTable {...slack.syncResourcesProps} />
					</CardContent>
				</Card>
			)}

			{ready && (
				<AdminSlackNotificationSettings
					key={slack.notificationSettingsKey}
					{...slack.notificationSettingsProps}
				/>
			)}

			{ready && <AdminSlackChannelsSettings {...slack.channelsSettingsProps} />}

			{hasConnection && <JobHistoryCard {...slack.jobHistoryProps} />}
		</PageLayout>
	);
}
