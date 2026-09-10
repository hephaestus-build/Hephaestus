import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, redirect } from "@tanstack/react-router";

import {
	getMemberOnboardingLinkOptionsOptions,
	getMemberOnboardingOptions,
	getMemberOnboardingSettingsOptions,
	getMemberOnboardingSettingsQueryKey,
	updateMemberOnboardingSettingsMutation,
} from "@/api/@tanstack/react-query.gen";
import { WorkspaceOnboardingSettingsPage } from "@/components/admin/onboarding/WorkspaceOnboardingSettingsPage";
import { resolveWorkspaceMembership } from "@/integrations/auth/guard";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/onboarding")({
	head: workspaceAdminHead("Member onboarding"),
	beforeLoad: async ({ context, params }) => {
		const membership = await resolveWorkspaceMembership(context.queryClient, params.workspaceSlug);
		if (!hasMinimumWorkspaceRole(membership?.role, "OWNER"))
			throw redirect({ to: "/w/$workspaceSlug/admin/settings", params, replace: true });
	},
	component: OnboardingSettingsRoute,
});

function OnboardingSettingsRoute() {
	const { workspaceSlug } = Route.useParams();
	const path = { workspaceSlug };
	const queryClient = useQueryClient();
	const settings = useQuery(getMemberOnboardingSettingsOptions({ path }));
	const links = useQuery(getMemberOnboardingLinkOptionsOptions({ path }));
	const save = useMutation({
		...updateMemberOnboardingSettingsMutation(),
		onSuccess: (data) => {
			queryClient.setQueryData(getMemberOnboardingSettingsQueryKey({ path }), data);
			void queryClient.invalidateQueries(getMemberOnboardingOptions({ path }));
			void links.refetch();
		},
	});
	return (
		<WorkspaceOnboardingSettingsPage
			workspaceSlug={workspaceSlug}
			state={
				settings.isError || links.isError
					? {
							status: "error",
							error: settings.error ?? links.error,
							onRetry: () => {
								void settings.refetch();
								void links.refetch();
							},
						}
					: settings.isPending || links.isPending
						? { status: "loading" }
						: { status: "ready", settings: settings.data, links: links.data }
			}
			saving={save.isPending}
			saveError={save.error ? problemDetailOf(save.error) : undefined}
			onSave={(body) => save.mutate({ path, body })}
		/>
	);
}
