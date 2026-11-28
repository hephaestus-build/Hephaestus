import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { currentTenantSlug } from "@/runtime/workspace-address";

import { PageSkeleton } from "@/components/layout/PageSkeleton";
import { consentIsPending, resolveCurrentUser, startGateReads } from "@/runtime/auth/guard";

export const Route = createFileRoute("/_authenticated")({
	beforeLoad: async ({ context, location, matches }) => {
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
		const workspaceSlug = matches.find(
			(match) => match.routeId === "/_authenticated/w/$workspaceSlug",
		)?.params.workspaceSlug;
		startGateReads(
			context.queryClient,
			workspaceSlug === undefined
				? undefined
				: { slug: workspaceSlug, pathname: location.pathname },
		);
	},
	pendingComponent: PageSkeleton,
	component: Outlet,
});
