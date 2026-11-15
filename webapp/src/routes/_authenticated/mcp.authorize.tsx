import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";

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
import environment from "@/environment";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { pageTitle } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf, stepUpChallengeOf } from "@/lib/problem-detail";

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
		request:
			typeof search.request === "string" && UUID.test(search.request) ? search.request : undefined,
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
			window.location.replace(
				`${environment.serverUrl}/mcp/oauth/authorize${window.location.search}`,
			);
		}
	}, [resumesAuthorization]);

	const consent = useQuery({
		...getMcpConsentRequestOptions({ path: { requestId: request ?? "" } }),
		enabled: request !== undefined,
		retry: false,
	});
	const [unsafeLocation, setUnsafeLocation] = useState(false);
	// The server checked the client's redirect; a scheme other than HTTP(S) still never runs here.
	const leave = (location: string) => {
		if (/^https?:\/\//iu.test(location)) {
			window.location.assign(location);
		} else {
			setUnsafeLocation(true);
		}
	};
	const approve = useMutation({
		...approveMcpConsentRequestMutation(),
		onSuccess: (answer) => leave(answer.redirectUri),
	});
	const refuse = useMutation({
		...refuseMcpConsentRequestMutation(),
		onSuccess: (answer) => leave(answer.redirectUri),
	});
	// Approving grants access, so the server may ask for a recent sign-in. Signing in again returns here,
	// to the same request, which waits 10 minutes.
	const challenge = approve.isError ? stepUpChallengeOf(approve.error) : undefined;
	const confirmAccess = useConfirmAccess(challenge !== undefined);

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
						onRetry: () => {
							void consent.refetch();
						},
					};
	} else if (unsafeLocation) {
		state = { status: "invalid", reason: "INVALID_REDIRECT_URI" };
	} else if ((approve.isError && challenge === undefined) || refuse.isError) {
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
		// A successful answer stays pending while the browser leaves for the client.
		let pending: "approve" | "refuse" | undefined;
		if (approve.isPending || approve.isSuccess) {
			pending = "approve";
		} else if (refuse.isPending || refuse.isSuccess) {
			pending = "refuse";
		}
		state = {
			status: "ready",
			request: consent.data,
			pending,
			onApprove: () => approve.mutate({ path }),
			onRefuse: () => refuse.mutate({ path }),
		};
	}

	return (
		<McpConsentPage
			state={state}
			confirmAccess={{
				open: challenge !== undefined,
				onOpenChange: (open) => {
					if (!open) {
						approve.reset();
					}
				},
				maxAgeSeconds: challenge?.maxAgeSeconds,
				providers: confirmAccess.providers,
				loading: confirmAccess.loading,
				error: confirmAccess.error,
				onRetry: confirmAccess.retry,
				onSignIn: confirmAccess.signIn,
			}}
		/>
	);
}
