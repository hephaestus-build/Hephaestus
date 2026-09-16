import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";
import { hasText } from "@/lib/text";

/** Workspace gate: a directory layout, so every route under `w/$workspaceSlug/` inherits it. */
// The gate answers on navigation only. A workspace revoked while a reader sits on one of its pages
// surfaces as the server's own 403s rather than a redirect; recovering mid-session meant carrying
// the old path into another workspace, which is the navigation this route exists to prevent.
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug")({
	beforeLoad: async ({ context, params }) => {
		// A list that cannot be fetched is not revoked access: keep the route rather than evicting
		// the reader on a network error.
		const workspaces = await context.queryClient
			.query(listWorkspacesOptions())
			.catch(() => undefined);
		if (!workspaces) {
			return;
		}
		if (workspaces.some((workspace) => workspace.workspaceSlug === params.workspaceSlug)) {
			return;
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
	component: () => <Outlet />,
});
