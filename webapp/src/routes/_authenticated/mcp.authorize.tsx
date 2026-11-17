import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute, useLocation, useRouter } from "@tanstack/react-router";
import { useEffect, useState } from "react";

import {
	approveMcpConsentRequestMutation,
	getMcpConsentRequestOptions,
	refuseMcpConsentRequestMutation,
} from "@/api/@tanstack/react-query.gen";
import {
	type McpAuthorizationError,
	McpConsentPage,
	type McpConsentPasskey,
	type McpConsentState,
} from "@/components/auth/McpConsentPage";
import environment from "@/environment";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { usePasskeySettings } from "@/hooks/use-passkey-settings";
import { pageTitle } from "@/lib/page-title";
import { problemDetailOf, problemStatusOf, stepUpChallengeOf } from "@/lib/problem-detail";
import { PASSKEY_CHALLENGE_PATH } from "@/runtime/auth/passkey-challenge";

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
	// Where the workspace's admin passkey policy asks for a passkey, Allow is the passkey step-up that
	// every admin change uses, and the approval follows once the server accepted it. The grant then
	// holds that confirmation for one hour.
	const router = useRouter();
	const location = useLocation();
	const passkeys = usePasskeySettings({
		signInReturnTo: location.href,
		onVerified: () => {
			if (request !== undefined) {
				approve.mutate({ path: { requestId: request } });
			}
		},
	});
	const needsPasskey = consent.data?.passkeyRequired === true && consent.data.refusal === undefined;
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
	} else if (
		request === undefined ||
		consent.isPending ||
		(needsPasskey && passkeys.section.loading)
	) {
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
		let pending: "passkey" | "approve" | "refuse" | undefined;
		if (passkeys.section.pending && passkeys.section.pendingAction === "verify") {
			pending = "passkey";
		} else if (approve.isPending || approve.isSuccess) {
			pending = "approve";
		} else if (refuse.isPending || refuse.isSuccess) {
			pending = "refuse";
		}
		let passkey: McpConsentPasskey | undefined;
		if (needsPasskey) {
			if (!passkeys.section.supported) {
				passkey = { status: "unsupported" };
			} else if (passkeys.section.status?.credentials.length === 0) {
				passkey = {
					status: "add",
					// The challenge page adds a passkey, confirms with it, and returns to this request.
					onAdd: () => {
						void router.navigate({
							to: PASSKEY_CHALLENGE_PATH,
							search: { returnTo: location.href },
						});
					},
				};
			} else {
				passkey = { status: "confirm", error: passkeys.section.error };
			}
		}
		state = {
			status: "ready",
			request: consent.data,
			passkey,
			pending,
			onApprove: needsPasskey ? passkeys.section.onVerify : () => approve.mutate({ path }),
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
