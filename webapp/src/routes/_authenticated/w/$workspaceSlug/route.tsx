import { createFileRoute, Outlet, redirect, useMatch } from "@tanstack/react-router";
import { hasText } from "@/lib/text";

import { getWorkspaceOptions } from "@/api/@tanstack/react-query.gen";
import { PublicActivityOnboardingDialog } from "@/components/onboarding/PublicActivityOnboardingDialog";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { usePublicActivityOnboarding } from "@/hooks/use-public-activity-onboarding";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { useAuth } from "@/runtime/auth/AuthContext";
import {
	isAppAdmin,
	memberOnboardingGateOptions,
	onboardingGateApplies,
	resolveCurrentUser,
	workspaceListGateOptions,
} from "@/runtime/auth/guard";
import { getUserViewSession } from "@/runtime/user-view/session";

/** Workspace gate: a directory layout, so every route under `w/$workspaceSlug/` inherits it. */
// The gate answers on navigation only. A workspace revoked while a reader sits on one of its pages
// surfaces as the server's own 403s rather than a redirect; recovering mid-session meant carrying
// the old path into another workspace, which is the navigation this route exists to prevent.
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug")({
	// Child guards receive membership or a verified administration target at navigation.
	// Components observe live workspace facts through `useWorkspaceAccess`.
	beforeLoad: async ({ context, params, location }) => {
		const workspaces = await context.queryClient
			.query(workspaceListGateOptions())
			.catch(() => undefined);
		const workspace = workspaces?.find(
			(candidate) => candidate.workspaceSlug === params.workspaceSlug,
		);
		if (workspace) {
			// An outage must never revoke membership, so an unread setup state lets the member in.
			if (!onboardingGateApplies(params.workspaceSlug, location.pathname)) {
				return { workspace, administrationWorkspace: undefined };
			}
			const onboarding = await context.queryClient
				.query(memberOnboardingGateOptions(params.workspaceSlug))
				.catch(() => undefined);
			if (onboarding?.needsSetup === true) {
				throw redirect({
					to: "/w/$workspaceSlug/onboarding",
					params,
					search: { returnTo: location.href },
					replace: true,
				});
			}
			return { workspace, administrationWorkspace: undefined };
		}

		const adminPath = `/w/${encodeURIComponent(params.workspaceSlug)}/admin`;
		if (
			getUserViewSession() === undefined &&
			(location.pathname === adminPath || location.pathname.startsWith(`${adminPath}/`)) &&
			isAppAdmin(await resolveCurrentUser(context.queryClient))
		) {
			// This verifies the target through the server's audited ADMIN elevation, not a membership.
			const administrationWorkspace = await context.queryClient.query({
				...getWorkspaceOptions({ path: { workspaceSlug: params.workspaceSlug } }),
				staleTime: 0,
				retry: false,
			});
			return { workspace: undefined, administrationWorkspace };
		}

		// A list that cannot be fetched is not revoked access: keep the route rather than evicting
		// the reader on a network error.
		if (!workspaces) {
			return { workspace: undefined, administrationWorkspace: undefined };
		}

		const fallbackSlug = workspaces[0]?.workspaceSlug;
		if (!hasText(fallbackSlug)) {
			throw redirect({ to: "/", replace: true });
		}
		throw redirect({
			to: "/w/$workspaceSlug",
			params: { workspaceSlug: fallbackSlug },
			replace: true,
		});
	},
	component: WorkspaceLayout,
});

/**
 * Where a workspace publishes its activity, the first page a member opens asks whether they show on
 * it. The AI setup comes first: it is a route of its own, which the step waits out. A user view
 * answers for no one.
 */
function WorkspaceLayout() {
	const { workspaceSlug } = Route.useParams();
	const { userView } = useAuth();
	const { workspaces } = useActiveWorkspaceSlug();
	const workspace = workspaces.find((candidate) => candidate.workspaceSlug === workspaceSlug);
	const settingUp =
		useMatch({
			from: "/_authenticated/w/$workspaceSlug/onboarding",
			shouldThrow: false,
		}) !== undefined;
	const onboarding = usePublicActivityOnboarding({
		workspaceSlug,
		enabled: workspace?.publishesPublicActivity === true && userView === undefined && !settingUp,
	});
	return (
		<>
			<Outlet />
			{workspace !== undefined && (
				<PublicActivityOnboardingDialog
					{...onboarding}
					workspaceName={workspace.displayName}
					providerType={toScmProviderType(workspace.providerType)}
				/>
			)}
		</>
	);
}
