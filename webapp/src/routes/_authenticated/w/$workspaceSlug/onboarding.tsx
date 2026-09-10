import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	completeMemberOnboardingMutation,
	dismissMemberOnboardingMutation,
	getMemberOnboardingOptions,
	getMemberOnboardingQueryKey,
	updateMemberAiChoiceMutation,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { WorkspaceOnboardingPage } from "@/components/onboarding/WorkspaceOnboardingPage";
import { useAuth } from "@/integrations/auth/AuthContext";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/onboarding")({
	component: OnboardingRoute,
});

function OnboardingRoute() {
	const { workspaceSlug } = Route.useParams();
	const navigate = Route.useNavigate();
	const queryClient = useQueryClient();
	const { linkAccount } = useAuth();
	const path = { workspaceSlug };
	const query = useQuery(getMemberOnboardingOptions({ path }));
	const updateCache = (data: WorkspaceOnboarding) =>
		queryClient.setQueryData(getMemberOnboardingQueryKey({ path }), data);
	const leave = (data: WorkspaceOnboarding) => {
		updateCache(data);
		void navigate({ to: "/w/$workspaceSlug", params: path });
	};
	const choice = useMutation({ ...updateMemberAiChoiceMutation(), onSuccess: updateCache });
	const completion = useMutation({ ...completeMemberOnboardingMutation(), onSuccess: leave });
	const dismissal = useMutation({ ...dismissMemberOnboardingMutation(), onSuccess: leave });
	const error = choice.error ?? completion.error ?? dismissal.error;
	return (
		<WorkspaceOnboardingPage
			key={workspaceSlug}
			state={
				query.isPending
					? { status: "loading" }
					: query.isError
						? {
								status: "error",
								error: query.error,
								onRetry: () => {
									void query.refetch();
								},
							}
						: { status: "ready", data: query.data }
			}
			pending={
				choice.isPending
					? "choice"
					: completion.isPending
						? "completion"
						: dismissal.isPending
							? "dismissal"
							: undefined
			}
			saveError={error ? problemDetailOf(error) : undefined}
			onChoose={(value) => {
				completion.reset();
				dismissal.reset();
				choice.mutate({ path, body: { choice: value } });
			}}
			onComplete={() => {
				choice.reset();
				dismissal.reset();
				if (query.data) completion.mutate({ path, body: { revision: query.data.revision } });
			}}
			onDismiss={() => {
				choice.reset();
				completion.reset();
				dismissal.mutate({ path });
			}}
			onRefresh={() => {
				void query.refetch();
			}}
			onLink={(registrationId) =>
				linkAccount(registrationId, `/w/${encodeURIComponent(workspaceSlug)}/onboarding`)
			}
		/>
	);
}
