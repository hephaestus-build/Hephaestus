import { createFileRoute, redirect } from "@tanstack/react-router";

import { listWorkspacesOptions } from "@/api/@tanstack/react-query.gen";

/**
 * The workspace home is the Practice profile, and Activity where the workspace does not review
 * practices. Only a definite "off" picks Activity: the Practice profile sends that answer back here,
 * so this route must never send it there. The workspace list is the one the parent route has
 * already loaded; a list that cannot be fetched is not an answer, so the home stays the Practice
 * profile. The search travels with the redirect, since the app chrome reads its own params (a survey
 * link) from whatever page the home lands on.
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
				workspace?.practicesEnabled === false
					? "/w/$workspaceSlug/activity"
					: "/w/$workspaceSlug/practice-profile",
			params,
			search: location.search,
			replace: true,
		});
	},
});
