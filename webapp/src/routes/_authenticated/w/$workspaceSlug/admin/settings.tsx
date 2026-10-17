import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import type { ReactNode } from "react";

import { WorkspaceSettingsPage } from "@/components/admin/settings/WorkspaceSettingsPage";
import { NoWorkspace } from "@/components/common/NoWorkspace";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useWorkspacePublicActivity } from "@/hooks/use-workspace-public-activity";
import { workspaceAdminHead } from "@/lib/page-title";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/settings")({
	head: workspaceAdminHead("Workspace settings"),
	// A write keeps the workspace it was made in: its callbacks, its pending state and an open
	// confirmation do not move to the next workspace.
	remountDeps: ({ params }) => params.workspaceSlug,
	component: AdminSettings,
});

function AdminSettings() {
	const {
		adminWorkspaceSlug: workspaceSlug,
		isLoading: isWorkspaceLoading,
		administrationWorkspace,
		administrationWorkspaceSlug,
		chromeWorkspace,
		error,
		refetch,
	} = useWorkspaceAccess();
	const workspace =
		administrationWorkspaceSlug === undefined ? chromeWorkspace : administrationWorkspace;
	const publicActivity = useWorkspacePublicActivity({
		workspaceSlug,
		live:
			administrationWorkspaceSlug === undefined
				? chromeWorkspace?.publishesPublicActivity
				: undefined,
		workspaceStatus: administrationWorkspace?.status,
	});

	if (!hasText(workspaceSlug) && !isWorkspaceLoading && error === undefined) {
		return <NoWorkspace />;
	}

	let settings: ReactNode;
	if (error !== undefined) {
		settings = (
			<div className="max-w-4xl">
				<QueryErrorAlert
					error={error}
					title="We could not load workspace settings"
					onRetry={refetch}
				/>
			</div>
		);
	} else if (!hasText(workspaceSlug) || workspace === undefined) {
		settings = <Skeleton className="h-64 max-w-4xl rounded-xl" />;
	} else {
		settings = (
			<WorkspaceSettingsPage
				workspaceSlug={workspaceSlug}
				practicesEnabled={workspace.practicesEnabled}
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
