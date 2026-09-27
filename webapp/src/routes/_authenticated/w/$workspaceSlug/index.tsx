import { createFileRoute, redirect } from "@tanstack/react-router";

/**
 * The workspace home is your Activity: what waits on you first. The search travels with it, since the
 * app chrome reads its own params (a survey link) from whatever page the home lands on.
 */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/")({
	beforeLoad: ({ params, location }) => {
		throw redirect({
			to: "/w/$workspaceSlug/activity",
			params,
			search: location.search,
			replace: true,
		});
	},
});
