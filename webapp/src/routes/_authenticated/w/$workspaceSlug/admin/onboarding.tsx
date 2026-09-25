import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, redirect } from "@tanstack/react-router";

import {
	getMemberOnboardingLinkOptionsOptions,
	getMemberOnboardingOptions,
	getMemberOnboardingSettingsOptions,
	getMemberOnboardingSettingsQueryKey,
	updateMemberOnboardingSettingsMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	type SettingsSubmission,
	type WorkspaceOnboardingSettingsPageProps,
	WorkspaceOnboardingSettingsPage,
} from "@/components/admin/onboarding/WorkspaceOnboardingSettingsPage";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";
import { resolveWorkspaceMembership } from "@/runtime/auth/guard";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/onboarding")({
	head: workspaceAdminHead("Member onboarding"),
	remountDeps: ({ params }) => params.workspaceSlug,
	beforeLoad: async ({ context, params }) => {
		const membership = await resolveWorkspaceMembership(context.queryClient, params.workspaceSlug);
		if (!hasMinimumWorkspaceRole(membership?.role, "OWNER")) {
			throw redirect({ to: "/w/$workspaceSlug/admin/settings", params, replace: true });
		}
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
		onSuccess: (data, variables) => {
			const savedPath = variables.path;
			queryClient.setQueryData(getMemberOnboardingSettingsQueryKey({ path: savedPath }), data);
			void queryClient.invalidateQueries(getMemberOnboardingOptions({ path: savedPath }));
			void queryClient.invalidateQueries(
				getMemberOnboardingLinkOptionsOptions({ path: savedPath }),
			);
		},
	});
	let submission: SettingsSubmission = { status: "idle" };
	if (save.isPending) {
		submission = { status: "saving" };
	} else if (save.isError) {
		submission = { status: "error", message: problemDetailOf(save.error) };
	}
	let state: WorkspaceOnboardingSettingsPageProps["state"];
	if (settings.isError || links.isError) {
		state = {
			status: "error",
			error: settings.error ?? links.error,
			onRetry: () => {
				void settings.refetch();
				void links.refetch();
			},
		};
	} else if (settings.isPending || links.isPending) {
		state = { status: "loading" };
	} else {
		state = {
			status: "ready",
			settings: settings.data,
			links: links.data,
			submission,
			onSave: async (body) => save.mutateAsync({ path, body }),
		};
	}
	return <WorkspaceOnboardingSettingsPage workspaceSlug={workspaceSlug} state={state} />;
}
