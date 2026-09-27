import { createFileRoute, redirect } from "@tanstack/react-router";

import { memberLevel } from "@/components/activity/activity-search";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";

/** Keeps /user/{username} links working: they open the member's level on Workspace activity. */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/user/$username/")({
	beforeLoad: ({ params }) => {
		throw redirect({
			to: "/w/$workspaceSlug/workspace-activity",
			params: { workspaceSlug: params.workspaceSlug },
			search: { detail: [detailStackKey(memberLevel(params.username))] },
			replace: true,
		});
	},
});
