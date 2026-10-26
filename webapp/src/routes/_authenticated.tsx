import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { currentTenantSlug } from "@/runtime/workspace-address";

import { PageSkeleton } from "@/components/layout/PageSkeleton";
import { consentIsPending, resolveCurrentUser } from "@/runtime/auth/guard";
import { getUserViewSession } from "@/runtime/user-view/session";

export const Route = createFileRoute("/_authenticated")({
	beforeLoad: async ({ context, location }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (!user) {
			throw redirect({
				to: "/login",
				search: { returnTo: location.href },
			});
		}
		if (await consentIsPending(context.queryClient)) {
			throw redirect({
				to: "/consent",
				search: { returnTo: location.href },
				mask:
					currentTenantSlug() === undefined
						? { to: location.pathname, search: location.search, hash: location.hash }
						: undefined,
			});
		}
		const viewed = getUserViewSession();
		if (viewed) {
			const workspacePath = `/w/${viewed.workspaceSlug}`;
			if (
				(location.pathname !== workspacePath &&
					!location.pathname.startsWith(`${workspacePath}/`)) ||
				location.pathname.startsWith(`${workspacePath}/admin`) ||
				location.pathname === `${workspacePath}/onboarding`
			) {
				throw redirect({
					to: "/w/$workspaceSlug",
					params: { workspaceSlug: viewed.workspaceSlug },
					replace: true,
				});
			}
		}
	},
	pendingComponent: PageSkeleton,
	component: Outlet,
});
