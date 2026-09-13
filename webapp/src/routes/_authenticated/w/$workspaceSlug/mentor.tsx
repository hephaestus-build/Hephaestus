import { useSuspenseQuery } from "@tanstack/react-query";
import {
	createFileRoute,
	Navigate,
	Outlet,
	useLocation,
	useMatchRoute,
} from "@tanstack/react-router";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StandardPageSurface } from "@/components/core/StandardPageSurface";
import { WorkspaceMentorPreferenceNotice } from "@/components/onboarding/WorkspaceMentorPreferenceNotice";
import { Spinner } from "@/components/ui/spinner";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useFeatureFlag } from "@/integrations/feature-flags";
import { mentorPreferenceReason } from "@/lib/mentor-preference";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor")({
	staticData: { surface: "fullscreen" },
	// Usually a cache hit, since the workspace gate fetched this on the way in; when the gate's
	// fetch failed, a cold-load outage reaches the router error surface rather than a skeleton.
	loader: ({ context, params }) =>
		context.queryClient.query({
			...getMemberOnboardingOptions({ path: params }),
			staleTime: "static",
		}),
	component: MentorLayout,
});

function MentorLayout() {
	const { workspaceSlug } = Route.useParams();
	const isThread = Boolean(useMatchRoute()({ to: "/w/$workspaceSlug/mentor/$threadId" }));
	const returnTo = useLocation().href;
	// The loader settled this; a refetch that fails later keeps the last answer on screen rather
	// than hiding a readable conversation behind an alert.
	const preference = useSuspenseQuery(getMemberOnboardingOptions({ path: { workspaceSlug } }));
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const mentorEnabled = featureState.features?.mentorEnabled;
	const { enabled: hasMentorAccess, isLoading: accessLoading } = useFeatureFlag("MENTOR_ACCESS");

	if (
		!featureState.isLoading &&
		!featureState.isError &&
		!accessLoading &&
		(mentorEnabled === false || !hasMentorAccess)
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

	if (featureState.isLoading || accessLoading || mentorEnabled !== true || !hasMentorAccess) {
		return (
			<div className="flex min-h-0 flex-1 items-center justify-center">
				<Spinner className="h-8 w-8" />
			</div>
		);
	}

	const notice = mentorPreferenceReason(preference.data);
	if (notice)
		return (
			<div className="flex min-h-0 flex-1 flex-col">
				<WorkspaceMentorPreferenceNotice
					workspaceSlug={workspaceSlug}
					returnTo={returnTo}
					notice={notice}
				/>
				{isThread && <Outlet />}
			</div>
		);
	return <Outlet />;
}
