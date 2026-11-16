import { createFileRoute } from "@tanstack/react-router";
import { WebhookIcon } from "lucide-react";
import { z } from "zod";
import { WorkspaceProviderRepairs } from "@/routes/_authenticated/-provider-repairs";

import { getWorkspaceOptions } from "@/api/@tanstack/react-query.gen";

import { WorkspaceConnectionStateNotice } from "@/components/admin/integrations/WorkspaceConnectionStateNotice";
import { WorkspaceGitLabProjectSelection } from "@/components/admin/integrations/WorkspaceGitLabProjectSelection";
import { WorkspaceIntegrationCardHeading } from "@/components/admin/integrations/WorkspaceIntegrationCardHeading";
import { WorkspaceJobHistoryCard } from "@/components/admin/integrations/WorkspaceJobHistoryCard";
import { WorkspaceRepositoriesSettings } from "@/components/admin/integrations/WorkspaceRepositoriesSettings";
import { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import { WorkspaceStoppedGitLabProjects } from "@/components/admin/integrations/WorkspaceStoppedGitLabProjects";
import { WorkspaceSyncResourcesTable } from "@/components/admin/integrations/WorkspaceSyncResourcesTable";
import { WorkspaceSyncStatusHeader } from "@/components/admin/integrations/WorkspaceSyncStatusHeader";
import { OutboundMark } from "@/components/common/outbound-link";
import { GitHubIcon, GitLabIcon } from "@/components/icons/brand";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { useScmIntegration } from "@/hooks/use-scm-integration";
import { workspaceAdminHead } from "@/lib/page-title";
import { scmProviderOf } from "@/lib/scm-provider";

const SCM_ICONS = { GITHUB: GitHubIcon, GITLAB: GitLabIcon };

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/scm")({
	validateSearch: z.object({
		repairConnection: z.number().int().positive().optional().catch(undefined),
	}),
	// The tab names the provider, as the sidebar link and the heading do. The workspace gate usually
	// knows it; when its list read failed, the scoped details the page reads anyway supply it.
	loader: async ({ context, params }) => {
		const known = context.workspace?.providerType ?? context.administrationWorkspace?.kind;
		if (known !== undefined) {
			return { workspaceKind: known };
		}
		const details = await context.queryClient
			.query({ ...getWorkspaceOptions({ path: params }), staleTime: "static", retry: false })
			.catch(() => undefined);
		return { workspaceKind: details?.kind };
	},
	head: ({ loaderData }) => workspaceAdminHead(scmProviderOf(loaderData?.workspaceKind).label)(),
	remountDeps: ({ params }) => params.workspaceSlug,
	component: ScmIntegrationPage,
});

function ScmIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const scm = useScmIntegration(slug);
	const repairId = Route.useSearch().repairConnection ?? scm.repairConnectionId;
	const { kind, label, hasConnection, isConnectionActive, isAppInstallationWorkspace } = scm;
	const ProviderIcon = kind === undefined ? WebhookIcon : SCM_ICONS[kind];

	let credentialRecovery = "Reconnect this source-control integration before you replace its token";
	if (isAppInstallationWorkspace) {
		credentialRecovery = "Reconnect through the GitHub App installation";
	} else if (isConnectionActive) {
		credentialRecovery = "Replace it using the personal access token form below";
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<ProviderIcon className="size-6" />}
				title={label}
				description={`Connection health, repositories and sync activity for this workspace’s ${label} connection.`}
			/>

			{hasConnection && (
				<WorkspaceConnectionStateNotice
					connectionState={scm.connectionState}
					credentialsUnreadableSince={scm.credentialsUnreadableSince}
					providerCleanupUnconfirmed={scm.providerCleanupUnconfirmed}
					credentialRecovery={credentialRecovery}
					displayName={label}
				/>
			)}

			<WorkspaceSyncStatusHeader
				label={label}
				actions={
					isAppInstallationWorkspace ? (
						<a
							href="https://github.com/settings/installations"
							target="_blank"
							rel="noreferrer"
							className={buttonVariants({ variant: "outline", size: "sm" })}
						>
							Manage installation on GitHub
							<OutboundMark />
						</a>
					) : undefined
				}
				{...scm.syncStatusHeaderProps}
			/>

			{(isConnectionActive || scm.canReconnectGitLab) && kind && !isAppInstallationWorkspace && (
				<WorkspaceScmTokenSettings {...scm.tokenSettingsProps} />
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<WorkspaceIntegrationCardHeading>Repository sync state</WorkspaceIntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<WorkspaceSyncResourcesTable {...scm.syncResourcesProps} />
					</CardContent>
				</Card>
			)}

			{scm.followsGitLabGroup && <WorkspaceGitLabProjectSelection {...scm.projectSelectionProps} />}

			{isConnectionActive && !isAppInstallationWorkspace && (
				<WorkspaceRepositoriesSettings {...scm.repositoriesSettingsProps} />
			)}

			{scm.followsGitLabGroup && <WorkspaceStoppedGitLabProjects {...scm.stoppedProjectsProps} />}

			{hasConnection && <WorkspaceJobHistoryCard {...scm.jobHistoryProps} />}
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
