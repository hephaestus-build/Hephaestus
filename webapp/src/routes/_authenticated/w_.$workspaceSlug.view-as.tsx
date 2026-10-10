import { createFileRoute, redirect } from "@tanstack/react-router";

import { instanceAdminHead } from "@/lib/page-title";
import { isAppAdmin, resolveCurrentUser } from "@/runtime/auth/guard";
import { userViewSearchSchema, WorkspaceUsersRoute } from "./-WorkspaceUsersRoute";

export const Route = createFileRoute("/_authenticated/w_/$workspaceSlug/view-as")({
	head: instanceAdminHead("View as user"),
	validateSearch: userViewSearchSchema,
	beforeLoad: async ({ context }) => {
		if (!isAppAdmin(await resolveCurrentUser(context.queryClient))) {
			throw redirect({ to: "/" });
		}
	},
	component: WorkspaceUsers,
});

function WorkspaceUsers() {
	return (
		<WorkspaceUsersRoute
			workspaceSlug={Route.useParams().workspaceSlug}
			search={Route.useSearch()}
		/>
	);
}
