import { createFileRoute, redirect } from "@tanstack/react-router";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";

/**
 * The workspace home is the Practice profile, and Activity where the workspace does not review
 * practices. The Practice profile sends a definite "off" back here, so this route never sends that
 * answer there. The workspace list is the one the parent route has already loaded; when it cannot be
 * fetched, the Practice profile could only show that error, so the home is Activity, which does not
 * need the list. The search travels with the redirect, since the app chrome reads its own params (a
 * survey link) from whatever page the home lands on.
 */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/")({
	beforeLoad: async ({ context, params, location }) => {
		const workspaces = await context.queryClient
			.query(listWorkspacesOptions())
			.catch(() => undefined);
		const workspace = workspaces?.find(
			(candidate) => candidate.workspaceSlug === params.workspaceSlug,
		);
		throw redirect({
			to:
				workspaces === undefined || workspace?.practicesEnabled === false
					? "/w/$workspaceSlug/activity"
					: "/w/$workspaceSlug/practice-profile",
			params,
			search: location.search,
			replace: true,
		});
	},
});
