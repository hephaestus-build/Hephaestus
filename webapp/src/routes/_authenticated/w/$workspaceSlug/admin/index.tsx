import { createFileRoute, redirect } from "@tanstack/react-router";

/** Administration has no page of its own: its address opens the workspace settings. */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/")({
	beforeLoad: ({ params }) => {
		throw redirect({ to: "/w/$workspaceSlug/admin/settings", params, replace: true });
	},
});
