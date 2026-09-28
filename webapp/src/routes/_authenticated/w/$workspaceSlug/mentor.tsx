import { useSuspenseQuery } from "@tanstack/react-query";
import { createFileRoute, Outlet, useLocation, useMatchRoute } from "@tanstack/react-router";

import { getMemberOnboardingOptions } from "@/api/@tanstack/react-query.gen";
import { WorkspaceMentorPreferenceNotice } from "@/components/onboarding/WorkspaceMentorPreferenceNotice";
import { mentorPreferenceReason } from "@/lib/mentor-preference";
import { useAuth } from "@/runtime/auth/AuthContext";
import { getUserViewSession } from "@/runtime/user-view/session";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor")({
	staticData: { surface: "fullscreen" },
	// Usually a cache hit, since the workspace gate fetched this on the way in; when the gate's
	// fetch failed, a cold-load outage reaches the router error surface rather than a skeleton.
	// A user view never asks for the saved AI choice: it belongs to the signed-in account.
	loader: async ({ context, params }) =>
		getUserViewSession() === undefined
			? context.queryClient.query({
					...getMemberOnboardingOptions({ path: params }),
					staleTime: "static",
				})
			: undefined,
	component: MentorLayout,
});

function MentorLayout() {
	const { workspaceSlug } = Route.useParams();
	const { userView } = useAuth();
	return userView ? <Outlet /> : <MentorPreferenceGate workspaceSlug={workspaceSlug} />;
}

function MentorPreferenceGate({ workspaceSlug }: { workspaceSlug: string }) {
	const isThread = Boolean(useMatchRoute()({ to: "/w/$workspaceSlug/mentor/$threadId" }));
	const returnTo = useLocation().href;
	// The loader settled this; a refetch that fails later keeps the last answer on screen rather
	// than hiding a readable conversation behind an alert.
	const preference = useSuspenseQuery(getMemberOnboardingOptions({ path: { workspaceSlug } }));
	const notice = mentorPreferenceReason(preference.data);
	if (notice) {
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
	}
	return <Outlet />;
}
