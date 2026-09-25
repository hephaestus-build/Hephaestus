import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";

import { Spinner } from "@/components/ui/spinner";
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
				mask: { to: location.pathname, search: location.search, hash: location.hash },
			});
		}
		const viewed = getUserViewSession();
		if (viewed) {
			const workspacePath = `/w/${viewed.workspaceSlug}`;
			if (
				(location.pathname !== workspacePath &&
					!location.pathname.startsWith(`${workspacePath}/`)) ||
				location.pathname.startsWith(`${workspacePath}/admin`)
			) {
				throw redirect({
					to: "/w/$workspaceSlug/user/$username",
					params: { workspaceSlug: viewed.workspaceSlug, username: viewed.login },
					replace: true,
				});
			}
		}
	},
	pendingComponent: () => (
		<div className="flex h-96 items-center justify-center">
			<Spinner className="size-8" />
		</div>
	),
	component: Outlet,
});
