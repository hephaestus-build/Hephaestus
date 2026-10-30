import { createFileRoute, redirect } from "@tanstack/react-router";
import { conversationSearchSchema } from "@/lib/conversation-search";

/** Links in sent Slack messages and weekly digests keep their message and thread. */
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/mentor")({
	validateSearch: conversationSearchSchema,
	beforeLoad: ({ params, search }) => {
		throw redirect({
			to: "/w/$workspaceSlug/conversation",
			params,
			search,
			hash: true,
			replace: true,
		});
	},
});
