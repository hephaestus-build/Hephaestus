import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";
import { z } from "zod";
import { hasText } from "@/lib/text";
import { toast } from "@/lib/toast";
import { WorkspaceProviderRepairs } from "@/routes/_authenticated/-provider-repairs";

import { WorkspaceConnectionStateNotice } from "@/components/admin/integrations/WorkspaceConnectionStateNotice";
import { WorkspaceIntegrationCardHeading } from "@/components/admin/integrations/WorkspaceIntegrationCardHeading";
import { WorkspaceJobHistoryCard } from "@/components/admin/integrations/WorkspaceJobHistoryCard";
import { WorkspaceSlackChannelsSettings } from "@/components/admin/integrations/WorkspaceSlackChannelsSettings";
import { WorkspaceSlackConnectionSettings } from "@/components/admin/integrations/WorkspaceSlackConnectionSettings";
import { WorkspaceSyncResourcesTable } from "@/components/admin/integrations/WorkspaceSyncResourcesTable";
import { WorkspaceSyncStatusHeader } from "@/components/admin/integrations/WorkspaceSyncStatusHeader";
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
	validateSearch: z.object({
		repairConnection: z.number().int().positive().optional().catch(undefined),
		status: z.enum(["success", "error"]).optional().catch(undefined),
		reason: z.string().optional().catch(undefined),
		description: z.string().optional().catch(undefined),
	}),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: SlackIntegrationPage,
});

function SlackIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const { status, reason, description, repairConnection } = Route.useSearch();
	const navigate = Route.useNavigate();
	useEffect(() => {
		if (status === undefined) {
			return;
		}
		if (status === "success") {
			toast.success("Slack workspace connected", { id: "slack-connect-result" });
		} else {
			toast.error("We could not connect Slack", {
				id: "slack-connect-result",
				description: hasText(description) ? description : reason,
			});
		}
		void navigate({ search: { repairConnection }, replace: true, resetScroll: false });
	}, [status, reason, description, repairConnection, navigate]);
	const slack = useSlackIntegration(slug);
	const repairId = repairConnection ?? slack.repairConnectionId;
	const { hasConnection } = slack;
	const ready = !slack.isLoading && !slack.loadError;
	// A failed refresh that kept the connection leaves its management, and the drafts and pending
	// requests open in it, mounted beside the failure.
	const manageable = ready || slack.connectionRetained;

	return (
		<PageLayout>
			<PageHeader
				icon={<SlackIcon className="size-6" />}
				title="Slack"
				description="Connection, monitored channels and sync activity for this workspace’s Slack app."
			/>

			{slack.isLoading && <Skeleton className="h-48 w-full" />}

			{slack.loadError && (
				<QueryErrorAlert
					error={slack.loadError}
					title="We could not load the Slack connection"
					onRetry={slack.retryLoad}
				/>
			)}

			{ready && hasConnection && (
				<WorkspaceConnectionStateNotice
					connectionState={slack.connectionState}
					credentialsUnreadableSince={slack.credentialsUnreadableSince}
					providerCleanupUnconfirmed={slack.providerCleanupUnconfirmed}
					credentialRecovery="Replace it with Reconnect Slack below"
					displayName="Slack"
				/>
			)}

			{ready && hasConnection && (
				<WorkspaceSyncStatusHeader label="Slack" {...slack.syncStatusHeaderProps} />
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<WorkspaceIntegrationCardHeading>Channel sync state</WorkspaceIntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<WorkspaceSyncResourcesTable {...slack.syncResourcesProps} />
					</CardContent>
				</Card>
			)}

			{manageable && <WorkspaceSlackConnectionSettings {...slack.connectionSettingsProps} />}

			{manageable && <WorkspaceSlackChannelsSettings {...slack.channelsSettingsProps} />}

			{hasConnection && <WorkspaceJobHistoryCard {...slack.jobHistoryProps} />}
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
