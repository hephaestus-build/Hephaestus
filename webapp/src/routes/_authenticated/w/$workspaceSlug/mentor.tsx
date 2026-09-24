import { createFileRoute, Navigate, Outlet } from "@tanstack/react-router";

import { NoWorkspace } from "@/components/common/NoWorkspace";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StandardPageSurface } from "@/components/layout/StandardPageSurface";
import { Spinner } from "@/components/ui/spinner";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { hasText } from "@/lib/text";
import { useFeatureFlag } from "@/runtime/feature-flags/hooks";
import { getUserViewSession } from "@/runtime/user-view/session";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor")({
	staticData: { surface: "fullscreen" },
	component: MentorLayout,
});

function MentorLayout() {
	const viewed = getUserViewSession() !== undefined;
	const { workspaceSlug, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const mentorEnabled = featureState.features?.mentorEnabled;
	const { enabled: hasMentorAccess, isLoading: accessLoading } = useFeatureFlag("MENTOR_ACCESS");
	const canReadMentor = viewed || hasMentorAccess;
	const isAccessLoading = !viewed && accessLoading;

	if (!hasText(workspaceSlug) && !isWorkspaceLoading) {
		return (
			<StandardPageSurface className="h-full overflow-auto">
				<NoWorkspace />
			</StandardPageSurface>
		);
	}

	if (
		!featureState.isLoading &&
		!featureState.isError &&
		!isAccessLoading &&
		(mentorEnabled === false || !canReadMentor) &&
		hasText(workspaceSlug)
	) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	if (featureState.isError) {
		return (
			<StandardPageSurface className="h-full overflow-auto">
				<QueryErrorAlert
					error={featureState.error}
					title="Couldn't load workspace features"
					onRetry={featureState.refetch}
				/>
			</StandardPageSurface>
		);
	}

	if (featureState.isLoading || isAccessLoading || mentorEnabled !== true || !canReadMentor) {
		return (
			<div className="flex min-h-0 flex-1 items-center justify-center">
				<Spinner className="h-8 w-8" />
			</div>
		);
	}

	return <Outlet />;
}
