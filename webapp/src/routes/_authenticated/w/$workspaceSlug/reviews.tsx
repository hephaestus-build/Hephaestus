import { createFileRoute, Navigate, Outlet } from "@tanstack/react-router";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Spinner } from "@/components/ui/spinner";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/reviews")({
	component: ReviewActivityLayout,
});

/**
 * Review activity exists only where practices review the work, so a workspace with practice reviews
 * off does not have this surface at all — the sidebar drops the entry and a link kept from before,
 * or from another workspace, lands on the workspace home rather than on a page that can only
 * explain itself.
 */
function ReviewActivityLayout() {
	const { workspaceSlug } = Route.useParams();
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const practicesEnabled = featureState.features?.practicesEnabled;

	if (featureState.isError) {
		return (
			<QueryErrorAlert
				error={featureState.error}
				title="Couldn't load workspace features"
				onRetry={featureState.refetch}
			/>
		);
	}

	// Only a definite "off" redirects: sending someone away before the answer arrives would bounce
	// them out of a workspace that does review practices.
	if (practicesEnabled === false) {
		return <Navigate to="/w/$workspaceSlug" params={{ workspaceSlug }} replace />;
	}

	if (featureState.isLoading || practicesEnabled !== true) {
		return (
			<div className="flex min-h-0 flex-1 items-center justify-center">
				<Spinner className="h-8 w-8" />
			</div>
		);
	}

	return <Outlet />;
}
