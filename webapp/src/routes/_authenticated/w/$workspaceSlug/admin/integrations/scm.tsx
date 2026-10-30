import { createFileRoute } from "@tanstack/react-router";
import { ExternalLinkIcon, WebhookIcon } from "lucide-react";

import { ConnectionStateNotice } from "@/components/admin/integrations/ConnectionStateNotice";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { JobHistoryCard } from "@/components/admin/integrations/JobHistoryCard";
import { SyncResourcesTable } from "@/components/admin/integrations/SyncResourcesTable";
import { SyncStatusHeader } from "@/components/admin/integrations/SyncStatusHeader";
import { WorkspaceGitLabProjectSelection } from "@/components/admin/integrations/WorkspaceGitLabProjectSelection";
import { WorkspaceRepositoriesSettings } from "@/components/admin/integrations/WorkspaceRepositoriesSettings";
import { WorkspaceScmTokenSettings } from "@/components/admin/integrations/WorkspaceScmTokenSettings";
import { WorkspaceStoppedGitLabProjects } from "@/components/admin/integrations/WorkspaceStoppedGitLabProjects";
import { GitHubIcon, GitLabIcon } from "@/components/icons/brand";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { useScmIntegration } from "@/hooks/use-scm-integration";
import { workspaceAdminHead } from "@/lib/page-title";
import { getProviderTerms, toScmProviderType } from "@/lib/provider/provider-terms";

const SCM_ICONS = { GITHUB: GitHubIcon, GITLAB: GitLabIcon };

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/integrations/scm")({
	// The tab carries the provider's name, as the sidebar link and the page heading do.
	head: ({ match }) => {
		const provider =
			match.context.workspace?.providerType ?? match.context.administrationWorkspace?.kind;
		return workspaceAdminHead(
			provider === undefined
				? "Source control"
				: getProviderTerms(toScmProviderType(provider)).displayName,
		)();
	},
	remountDeps: ({ params }) => params.workspaceSlug,
	component: ScmIntegrationPage,
});

function ScmIntegrationPage() {
	const { workspaceSlug: slug } = Route.useParams();
	const scm = useScmIntegration(slug);
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
				<ConnectionStateNotice
					connectionState={scm.connectionState}
					credentialsUnreadableSince={scm.credentialsUnreadableSince}
					credentialRecovery={credentialRecovery}
					displayName={label}
				/>
			)}

			<SyncStatusHeader
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
							<ExternalLinkIcon className="size-3.5" />
						</a>
					) : undefined
				}
				{...scm.syncStatusHeaderProps}
			/>

			{isConnectionActive && kind && !isAppInstallationWorkspace && (
				<WorkspaceScmTokenSettings {...scm.tokenSettingsProps} />
			)}

			{hasConnection && (
				<Card>
					<CardHeader>
						<IntegrationCardHeading>Repository sync state</IntegrationCardHeading>
					</CardHeader>
					<CardContent>
						<SyncResourcesTable {...scm.syncResourcesProps} />
					</CardContent>
				</Card>
			)}

			{scm.followsGitLabGroup && <WorkspaceGitLabProjectSelection {...scm.projectSelectionProps} />}

			{isConnectionActive && !isAppInstallationWorkspace && (
				<WorkspaceRepositoriesSettings {...scm.repositoriesSettingsProps} />
			)}

			{scm.followsGitLabGroup && <WorkspaceStoppedGitLabProjects {...scm.stoppedProjectsProps} />}

			{hasConnection && <JobHistoryCard {...scm.jobHistoryProps} />}
		</PageLayout>
	);
}
