import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";

import {
	approveMcpConsentRequestMutation,
	getMcpConsentRequestOptions,
	refuseMcpConsentRequestMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	type McpAuthorizationError,
	McpConsentPage,
	type McpConsentState,
} from "@/components/auth/McpConsentPage";
import { environment } from "@/environment";
import { pageTitle } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

const AUTHORIZATION_ERRORS: readonly McpAuthorizationError[] = [
	"INVALID_CLIENT",
	"INVALID_CLIENT_METADATA",
	"INVALID_REDIRECT_URI",
];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

interface McpAuthorizeSearch {
	request?: string;
	error?: McpAuthorizationError;
}

/**
 * The server's authorization endpoint sends the browser here three ways: with `request` once a consent
 * request waits, with `error` when the client cannot connect, and with the client's own authorization
 * parameters when nobody was signed in. The guard of `_authenticated` signs the account in first.
 */
export const Route = createFileRoute("/_authenticated/mcp/authorize")({
	staticData: { surface: "auth" },
	head: () => ({
		meta: [
			{ title: pageTitle("Connect an MCP client") },
			{ name: "robots", content: "noindex, nofollow" },
		],
	}),
	validateSearch: (search): McpAuthorizeSearch => ({
		request: typeof search.request === "string" && UUID.test(search.request) ? search.request : undefined,
		error: AUTHORIZATION_ERRORS.find((reason) => reason === search.error),
	}),
	component: McpAuthorizeRoute,
});

function McpAuthorizeRoute() {
	const { request, error } = Route.useSearch();
	const resumesAuthorization = request === undefined && error === undefined;

	useEffect(() => {
		if (resumesAuthorization) {
			// Signed in now, so the server's authorization endpoint can create the consent request.
			window.location.replace(`${environment.serverUrl}/mcp/oauth/authorize${window.location.search}`);
		}
	}, [resumesAuthorization]);

	const consent = useQuery({
		...getMcpConsentRequestOptions({ path: { requestId: request ?? "" } }),
		enabled: request !== undefined,
		retry: false,
	});
	const leave = (location: string) => window.location.assign(location);
	const approve = useMutation({
		...approveMcpConsentRequestMutation(),
		onSuccess: (answer) => leave(answer.location),
	});
	const refuse = useMutation({
		...refuseMcpConsentRequestMutation(),
		onSuccess: (answer) => leave(answer.location),
	});

	let state: McpConsentState;
	if (error !== undefined) {
		state = { status: "invalid", reason: error };
	} else if (request === undefined || consent.isPending) {
		state = { status: "loading" };
	} else if (consent.isError) {
		state =
			problemStatusOf(consent.error) === 404
				? { status: "gone" }
				: {
						status: "error",
						message: problemDetailOf(consent.error, "The request could not be loaded."),
						onRetry: () => void consent.refetch(),
					};
	} else if (approve.isError || refuse.isError) {
		state = {
			status: "error",
			message: problemDetailOf(approve.error ?? refuse.error, "Your answer could not be sent."),
			onRetry: () => {
				approve.reset();
				refuse.reset();
				void consent.refetch();
			},
		};
	} else {
		const path = { requestId: request };
		state = {
			status: "ready",
			request: consent.data,
			// A successful answer stays pending while the browser leaves for the client.
			pending:
				approve.isPending || approve.isSuccess
					? "approve"
					: refuse.isPending || refuse.isSuccess
						? "refuse"
						: undefined,
			onApprove: () => approve.mutate({ path }),
			onRefuse: () => refuse.mutate({ path }),
		};
	}

	return <McpConsentPage state={state} />;
}
