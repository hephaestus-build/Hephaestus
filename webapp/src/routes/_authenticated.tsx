import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";

import { Spinner } from "@/components/ui/spinner";
import { consentIsPending, resolveCurrentUser } from "@/integrations/auth/guard";

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
	},
	pendingComponent: () => (
		<div className="flex items-center justify-center h-96">
			<Spinner className="size-8" />
		</div>
	),
	component: Outlet,
});
