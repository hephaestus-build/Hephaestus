import { createFileRoute, Outlet, redirect, useMatch, useMatches } from "@tanstack/react-router";
import { hasText } from "@/lib/text";

import {
	getMemberOnboardingOptions,
	getPublicActivityOnboardingOptions,
	getWorkspaceOptions,
	listWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import { PublicActivityNotice } from "@/components/onboarding/PublicActivityNotice";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { usePublicActivityOnboarding } from "@/hooks/use-public-activity-onboarding";
import { toScmProviderType } from "@/lib/provider/provider-terms";
import { useAuth } from "@/runtime/auth/AuthContext";
import { isAppAdmin, resolveCurrentUser } from "@/runtime/auth/guard";
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
			.query({ ...listWorkspacesOptions(), retry: false })
			.catch(() => undefined);
		const workspace = workspaces?.find(
			(candidate) => candidate.workspaceSlug === params.workspaceSlug,
		);
		if (workspace) {
			const base = `/w/${encodeURIComponent(params.workspaceSlug)}`;
			const userView = getUserViewSession() !== undefined;
			const settingUp = location.pathname === `${base}/onboarding`;
			// The public activity notice renders with the page, so nothing moves down after it loads.
			// An outage only leaves the notice to arrive later, as the member onboarding below does.
			const publicActivity =
				workspace.publishesPublicActivity && !userView && !settingUp
					? context.queryClient
							.query({
								...getPublicActivityOnboardingOptions({ path: { slug: params.workspaceSlug } }),
								retry: false,
							})
							.catch(() => undefined)
					: undefined;
			// Owners must be able to repair setup; an outage must never revoke membership. A user view
			// never completes setup: the saved choices belong to the signed-in account.
			if (userView || settingUp || location.pathname.startsWith(`${base}/admin`)) {
				await publicActivity;
				return { workspace, administrationWorkspace: undefined };
			}
			const onboarding = await context.queryClient
				.query({
					...getMemberOnboardingOptions({ path: { workspaceSlug: params.workspaceSlug } }),
					retry: false,
				})
				.catch(() => undefined);
			if (onboarding?.needsSetup === true) {
				throw redirect({
					to: "/w/$workspaceSlug/onboarding",
					params,
					search: { returnTo: location.href },
					replace: true,
				});
			}
			await publicActivity;
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
 * Where a workspace publishes its activity, a member's pages open with a notice that asks whether
 * they show on it, until they answer. The AI setup comes first: it is a route of its own, which the
 * notice waits out. A user view answers for no one, and an instance admin who administers the
 * workspace without a membership has no workspace here, so neither sees it.
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
	// A full-screen page has no page padding, so the notice brings its own.
	const fullscreen = useMatches({
		select: (matches) => matches.some((match) => match.staticData.surface === "fullscreen"),
	});
	const { notice, ...onboarding } = usePublicActivityOnboarding({
		workspaceSlug,
		enabled: workspace?.publishesPublicActivity === true && userView === undefined && !settingUp,
	});
	return (
		<>
			{workspace !== undefined && notice !== undefined && (
				<PublicActivityNotice
					{...notice}
					{...onboarding}
					workspaceName={workspace.displayName}
					providerType={toScmProviderType(workspace.providerType)}
					className={fullscreen ? "px-4 pt-4" : "pb-6"}
				/>
			)}
			<Outlet />
		</>
	);
}
