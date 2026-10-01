import { createFileRoute, redirect } from "@tanstack/react-router";

/**
 * The workspace home is the Practice profile where the workspace reviews practices, and Activity
 * everywhere else, including when the parent route could not read the workspace list. Activity
 * renders without that list, only naming work in the default provider's words, while the Practice
 * profile could only show the error. The search travels with the redirect, since the app chrome
 * reads its own params (a survey link) from whatever page the home lands on.
 */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/")({
	beforeLoad: ({ context, params, location }) => {
		throw redirect({
			to:
				context.workspace?.practicesEnabled === true
					? "/w/$workspaceSlug/practice-profile"
					: "/w/$workspaceSlug/activity",
			params,
			search: location.search,
			replace: true,
		});
	},
});
