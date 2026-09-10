import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Navigate, Outlet } from "@tanstack/react-router";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StandardPageSurface } from "@/components/core/StandardPageSurface";
import { WorkspaceMentorPreferenceNotice } from "@/components/onboarding/WorkspaceMentorPreferenceNotice";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { NoWorkspace } from "@/components/workspace/NoWorkspace";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { useFeatureFlag } from "@/integrations/feature-flags";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor")({
	staticData: { surface: "fullscreen" },
	component: MentorLayout,
});

function MentorLayout() {
	const { workspaceSlug, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();
	const preference = useQuery({
		...getMemberOnboardingOptions({ path: { workspaceSlug: workspaceSlug ?? "" } }),
		enabled: Boolean(workspaceSlug),
	});
	const featureState = useWorkspaceFeatures(workspaceSlug);
	const mentorEnabled = featureState.features?.mentorEnabled;
	const { enabled: hasMentorAccess, isLoading: accessLoading } = useFeatureFlag("MENTOR_ACCESS");

	if (!workspaceSlug && !isWorkspaceLoading) {
		return (
			<StandardPageSurface className="h-full overflow-auto">
				<NoWorkspace />
			</StandardPageSurface>
		);
	}

	if (
		!featureState.isLoading &&
		!featureState.isError &&
		!accessLoading &&
		(mentorEnabled === false || !hasMentorAccess) &&
		workspaceSlug
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

	if (preference.isError)
		return (
			<StandardPageSurface>
				<QueryErrorAlert
					title="Couldn't load your AI preference"
					error={preference.error}
					onRetry={() => {
						void preference.refetch();
					}}
				/>
			</StandardPageSurface>
		);
	if (preference.isPending)
		return (
			<StandardPageSurface>
				<section aria-label="Heph" aria-busy="true" className="mx-auto max-w-xl space-y-4 p-6">
					<Skeleton className="size-8" />
					<Skeleton className="h-7 w-3/4" />
					<Skeleton className="h-16 w-full" />
					<Skeleton className="h-9 w-44" />
				</section>
			</StandardPageSurface>
		);
	const choice = preference.data.aiChoice;
	const reason =
		choice === "NO_AI"
			? "no-ai"
			: choice == null && preference.data.aiChoiceRequired
				? "choice-required"
				: choice != null &&
					  !preference.data.aiOptions.some(
							(option) => option.choice === choice && option.mentorReady,
					  )
					? "unavailable"
					: undefined;
	if (reason && workspaceSlug)
		return (
			<StandardPageSurface>
				<WorkspaceMentorPreferenceNotice workspaceSlug={workspaceSlug} reason={reason} />
			</StandardPageSurface>
		);
	return <Outlet />;
}
