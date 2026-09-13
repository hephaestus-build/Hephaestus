import { useMutation } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { unsubscribeEmailMutation } from "@/api/@tanstack/react-query.gen";
import { createClient } from "@/api/client";
import { EmailUnsubscribePage } from "@/components/settings/EmailUnsubscribePage";
import environment from "@/environment";

// This capability must work independently of stale sessions, CSRF cookies and impersonation.
const anonymousClient = createClient({
	baseUrl: environment.serverUrl,
	credentials: "omit",
	referrerPolicy: "no-referrer",
});

export const Route = createFileRoute("/unsubscribe")({
	staticData: { surface: "auth" },
	head: () => ({
		meta: [
			{ title: "Unsubscribe — Hephaestus" },
			{ name: "referrer", content: "no-referrer" },
			{ name: "robots", content: "noindex, nofollow" },
		],
	}),
	validateSearch: (search): { token?: string } => ({
		token: typeof search.token === "string" && search.token.length > 0 ? search.token : undefined,
	}),
	component: UnsubscribeRoute,
});

function UnsubscribeRoute() {
	const { token } = Route.useSearch();
	return token ? (
		<UnsubscribeConfirmation key={token} token={token} />
	) : (
		<EmailUnsubscribePage state={{ status: "invalid" }} />
	);
}

function UnsubscribeConfirmation({ token }: { token: string }) {
	const unsubscribe = useMutation(unsubscribeEmailMutation({ client: anonymousClient }));
	if (unsubscribe.isSuccess) return <EmailUnsubscribePage state={{ status: "complete" }} />;
	return (
		<EmailUnsubscribePage
			state={{
				status: unsubscribe.isPending ? "pending" : unsubscribe.isError ? "error" : "confirm",
				onConfirm: () => {
					if (unsubscribe.isPending) return;
					unsubscribe.mutate({ path: { token }, body: { "List-Unsubscribe": "One-Click" } });
				},
			}}
		/>
	);
}
