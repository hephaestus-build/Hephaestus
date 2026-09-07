import { createFileRoute, Navigate, Outlet } from "@tanstack/react-router";

import { ReviewResultsSkeleton } from "@/components/admin/practice-reviews/ReviewResultsSkeleton";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TRACE_PAGE_SIZE } from "@/components/practice-trace/TraceListPage";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/reviews")({
	component: ReviewActivityLayout,
});

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
		return <ReviewResultsSkeleton label="Loading review activity" rows={TRACE_PAGE_SIZE} />;
	}

	return <Outlet />;
}
