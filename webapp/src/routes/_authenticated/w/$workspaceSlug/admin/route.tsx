import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";

import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";
import { resolveWorkspaceMembership } from "@/runtime/auth/guard";

/** Workspace-admin gate: a directory layout, so every route under `admin/` inherits it. */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin")({
	beforeLoad: async ({ context, params }) => {
		const membership = await resolveWorkspaceMembership(context.queryClient, params.workspaceSlug);
		if (!hasMinimumWorkspaceRole(membership?.role, "ADMIN")) {
			throw redirect({
				to: "/w/$workspaceSlug",
				params: { workspaceSlug: params.workspaceSlug },
				replace: true,
			});
		}
	},
	component: () => <Outlet />,
});
