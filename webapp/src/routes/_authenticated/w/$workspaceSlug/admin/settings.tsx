import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { Settings2 } from "lucide-react";
import type { ReactNode } from "react";
import { useWorkspacePasskeySettings } from "@/hooks/use-workspace-passkey-settings";

import {
	getWorkspaceQueryKey,
	listWorkspacesQueryKey,
	updateStatusMutation,
} from "@/api/@tanstack/react-query.gen";
import { SuspendedWorkspaceCard } from "@/components/admin/settings/SuspendedWorkspaceCard";
import { WorkspaceDangerZoneSettings } from "@/components/admin/settings/WorkspaceDangerZoneSettings";
import { WorkspacePasskeyPolicySection } from "@/components/admin/settings/WorkspacePasskeyPolicySection";
import { WorkspaceSettingsPage } from "@/components/admin/settings/WorkspaceSettingsPage";
import { WorkspaceSetupSummary } from "@/components/admin/settings/WorkspaceSetupSummary";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Skeleton } from "@/components/ui/skeleton";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useWorkspaceDangerZone } from "@/hooks/use-workspace-danger-zone";
import { useWorkspacePublicActivity } from "@/hooks/use-workspace-public-activity";
import { useWorkspaceSetup } from "@/hooks/use-workspace-setup";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";
import { toast } from "@/lib/toast";
import { NoWorkspaceContainer } from "@/routes/-NoWorkspaceContainer";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/settings")({
	head: workspaceAdminHead("Workspace settings"),
	// A write keeps the workspace it was made in: its callbacks, its pending state and an open
	// confirmation do not move to the next workspace.
	remountDeps: ({ params }) => params.workspaceSlug,
	component: AdminSettings,
});

const PAGE_HEADER = (
	<PageHeader
		icon={<Settings2 />}
		title="Workspace settings"
		description="What this workspace offers its members, and its lifecycle."
	/>
);

function AdminSettings() {
	const {
		adminWorkspaceSlug,
		administrationWorkspace,
		administrationWorkspaceSlug,
		chromeWorkspace,
	} = useWorkspaceAccess();
	const workspace =
		administrationWorkspaceSlug === undefined ? chromeWorkspace : administrationWorkspace;
	// A suspended workspace answers no settings read, so its page offers only what it can still do.
	if (hasText(adminWorkspaceSlug) && workspace?.status === "SUSPENDED") {
		return (
			<SuspendedSettings workspaceSlug={adminWorkspaceSlug} workspaceName={workspace.displayName} />
		);
	}
	return <ActiveSettings />;
}

function SuspendedSettings({
	workspaceSlug,
	workspaceName,
}: {
	workspaceSlug: string;
	workspaceName: string;
}) {
	const queryClient = useQueryClient();
	const dangerZone = useWorkspaceDangerZone(workspaceSlug);
	const resume = useMutation({
		...updateStatusMutation(),
		onSuccess: async () => {
			toast.success(`${workspaceName} is active again`);
			await Promise.all([
				queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() }),
				queryClient.invalidateQueries({
					queryKey: getWorkspaceQueryKey({ path: { workspaceSlug } }),
				}),
			]);
		},
	});
	return (
		<PageLayout>
			{PAGE_HEADER}
			<div className="max-w-4xl space-y-8">
				<SuspendedWorkspaceCard
					workspaceName={workspaceName}
					resuming={resume.isPending}
					resumeError={resume.error == null ? undefined : problemDetailOf(resume.error)}
					onResume={() => {
						resume.mutate({ path: { workspaceSlug }, body: { status: "ACTIVE" } });
					}}
				/>
				<WorkspaceDangerZoneSettings workspaceSlug={workspaceSlug} {...dangerZone} />
			</div>
		</PageLayout>
	);
}

function ActiveSettings() {
	const passkeys = useWorkspacePasskeySettings({ workspaceSlug: Route.useParams().workspaceSlug });
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
	const setup = useWorkspaceSetup(workspaceSlug ?? "", { enabled: hasText(workspaceSlug) });
	const dangerZone = useWorkspaceDangerZone(workspaceSlug ?? "");
	const { hiddenPeople, ...publicActivity } = useWorkspacePublicActivity({
		workspaceSlug,
		live:
			administrationWorkspaceSlug === undefined
				? chromeWorkspace?.publishesPublicActivity
				: undefined,
		workspaceStatus: administrationWorkspace?.status,
	});

	if (!hasText(workspaceSlug) && !isWorkspaceLoading && error === undefined) {
		return <NoWorkspaceContainer />;
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
				setupSummary={<WorkspaceSetupSummary workspaceSlug={workspaceSlug} state={setup} />}
				security={<WorkspacePasskeyPolicySection {...passkeys} />}
				workspaceSlug={workspaceSlug}
				practicesEnabled={workspace.practicesEnabled}
				publicActivity={{
					workspaceName: workspace.displayName,
					providerType: toScmProviderType(workspace.providerType),
					address: workspace.workspaceAddress,
					...publicActivity,
				}}
				hiddenPeople={hiddenPeople}
				dangerZone={dangerZone}
			/>
		);
	}

	return (
		<PageLayout>
			{PAGE_HEADER}
			{settings}
		</PageLayout>
	);
}
