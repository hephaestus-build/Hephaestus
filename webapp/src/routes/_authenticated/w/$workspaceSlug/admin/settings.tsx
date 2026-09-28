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
import { workspaceAdminHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/settings")({
	head: workspaceAdminHead("Workspace settings"),
	component: AdminSettings,
});

function AdminSettings() {
	const { workspaceSlug, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();
	const featureState = useWorkspaceFeatures(workspaceSlug);

	if (!hasText(workspaceSlug) && !isWorkspaceLoading) {
		return <NoWorkspace />;
	}

	let settings: ReactNode;
	if (featureState.isError) {
		settings = (
			<div className="max-w-4xl">
				<QueryErrorAlert
					error={featureState.error}
					title="Couldn't load workspace settings"
					onRetry={featureState.refetch}
				/>
			</div>
		);
	} else if (!hasText(workspaceSlug) || featureState.practicesEnabled === undefined) {
		settings = <Skeleton className="h-64 max-w-4xl rounded-xl" />;
	} else {
		settings = (
			<WorkspaceSettingsPage
				workspaceSlug={workspaceSlug}
				practicesEnabled={featureState.practicesEnabled}
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
