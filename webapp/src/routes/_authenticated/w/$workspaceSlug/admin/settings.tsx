import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import type { ReactNode } from "react";

import { WorkspaceSettingsPage } from "@/components/admin/settings/WorkspaceSettingsPage";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useWorkspacePublicActivity } from "@/hooks/use-workspace-public-activity";
import { workspaceAdminHead } from "@/lib/page-title";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/settings")({
	head: workspaceAdminHead("Workspace settings"),
	component: AdminSettings,
});

function AdminSettings() {
	const { workspaceSlug, isLoading: isWorkspaceLoading, workspaces } = useActiveWorkspaceSlug();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const workspace = workspaces.find((candidate) => candidate.workspaceSlug === workspaceSlug);
	const publicActivity = useWorkspacePublicActivity({
		workspaceSlug,
		live: workspace?.publishesPublicActivity === true,
	});

	if (!hasText(workspaceSlug) && !isWorkspaceLoading) {
		return <NoWorkspace />;
	}

	let settings: ReactNode;
	if (featureState.isError) {
		settings = (
			<div className="max-w-4xl">
				<QueryErrorAlert
					error={featureState.error}
					title="We could not load workspace settings"
					onRetry={featureState.refetch}
				/>
			</div>
		);
	} else if (
		!hasText(workspaceSlug) ||
		workspace === undefined ||
		featureState.practicesEnabled === undefined
	) {
		settings = <Skeleton className="h-64 max-w-4xl rounded-xl" />;
	} else {
		settings = (
			<WorkspaceSettingsPage
				workspaceSlug={workspaceSlug}
				practicesEnabled={featureState.practicesEnabled}
				publicActivity={{
					workspaceName: workspace.displayName,
					providerType: toScmProviderType(workspace.providerType),
					address: workspace.workspaceAddress,
					...publicActivity,
				}}
			/>
		);
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<Settings2 />}
				title="Workspace settings"
				description="What this workspace offers its members, and its lifecycle."
			/>
			{settings}
		</PageLayout>
	);
}
