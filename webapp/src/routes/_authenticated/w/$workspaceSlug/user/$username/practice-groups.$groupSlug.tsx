import { createFileRoute, redirect } from "@tanstack/react-router";

import { memberLevel } from "@/components/activity/activity-search";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";
import { practiceGroupLevel } from "@/components/practice-profile/practice-profile-search";
import { resolveWorkspaceMembership } from "@/runtime/auth/guard";

/**
 * Keeps /user/{username}/practice-groups/{group} links working. The practice profile is only ever the
 * reader's own, so the link opens the group's level there when it names the reader, and otherwise the
 * member it names on Workspace activity.
 */
export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/user/$username/practice-groups/$groupSlug",
)({
	beforeLoad: async ({ context, params }) => {
		const membership = await resolveWorkspaceMembership(context.queryClient, params.workspaceSlug);
		if (membership?.userLogin !== params.username) {
			throw redirect({
				to: "/w/$workspaceSlug/workspace-activity",
				params: { workspaceSlug: params.workspaceSlug },
				search: { detail: [detailStackKey(memberLevel(params.username))] },
				replace: true,
			});
		}
		throw redirect({
			to: "/w/$workspaceSlug/practice-profile",
			params: { workspaceSlug: params.workspaceSlug },
			search: { detail: [detailStackKey(practiceGroupLevel(params.groupSlug))] },
			replace: true,
		});
	},
});
