import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";

import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { pageHead } from "@/lib/page-title";
import { hasText } from "@/lib/text";
import { useAuth } from "@/runtime/auth/AuthContext";
import { safeReturnTo } from "@/runtime/auth/guard";

interface ErrorSearch {
	code?: string;
	/** Where linking was headed, set by the server on a recent-sign-in refusal. */
	returnTo?: string;
}

/** PII-free, friendly copy keyed by the server's auth-failure codes. */
const ERROR_COPY: Record<string, { title: string; description: string }> = {
	oauth_failure: {
		title: "Sign-in did not complete",
		description:
			"We could not finish signing you in with that provider. Please try again from the sign-in page.",
	},
	token_exchange: {
		title: "Sign-in could not be verified",
		description:
			"There was a problem confirming your identity with the provider. Please try signing in again.",
	},
	idp_unavailable: {
		title: "Provider unavailable",
		description:
			"The identity provider could not be reached right now. Please try again in a few moments.",
	},
	already_linked: {
		title: "Account already linked",
		description:
			"That provider identity is already linked to another account. Sign in with the original account instead.",
	},
	identity_already_linked: {
		title: "Account already linked",
		description:
			"That provider identity is already linked to another account. Sign in with the original account instead.",
	},
	link_requires_auth: {
		// Slack and Outline are both link-only: they can only be attached to an existing session.
		title: "Sign in before linking that account",
		description:
			"Open Hephaestus, sign in with GitHub or GitLab, then connect Slack or Outline from Settings.",
	},
	step_up_required: {
		// The session is valid and the account is right; only its age is the problem, so this must not
		// read as a rejected identity.
		title: "Confirm access before linking that account",
		description:
			"Linking an identity needs a recent sign-in. Confirm access with an identity already linked to your account, then link it again.",
	},
	client_not_registered: {
		// Reached inside the browser extension's sign-in window: the instance does not list that
		// extension, so the server refuses to hand it a sign-in rather than redirect to it.
		title: "This extension cannot sign in here",
		description:
			"This Hephaestus instance does not allow that browser extension to sign in. Ask an admin to add its extension ID, then try again from the extension.",
	},
	unknown_provider: {
		title: "Provider is not configured",
		description:
			"This Hephaestus instance does not have that sign-in provider configured. Ask an admin to check the login provider settings.",
	},
};

function describe(code: string | undefined): { title: string; description: string } {
	if (hasText(code) && ERROR_COPY[code]) {
		return ERROR_COPY[code];
	}
	return {
		title: "Something went wrong",
		description: "We hit an unexpected problem signing you in. Please try again.",
	};
}

export const Route = createFileRoute("/auth/error")({
	head: pageHead("Sign-in problem"),
	staticData: { surface: "auth" },
	validateSearch: (search): ErrorSearch => ({
		code: typeof search.code === "string" ? search.code : undefined,
		returnTo: typeof search.returnTo === "string" ? search.returnTo : undefined,
	}),
	component: AuthErrorPage,
});

function AuthErrorPage() {
	const { code, returnTo } = Route.useSearch();
	const { title, description } = describe(code);
	const { isAuthenticated } = useAuth();
	const recovery = code === "step_up_required";
	// Linking always names where it was headed; a refusal from before it did falls back to where linking lives.
	const destination = safeReturnTo(returnTo) === "/" ? "/settings" : safeReturnTo(returnTo);
	const [confirming, setConfirming] = useState(false);
	const confirmAccess = useConfirmAccess(confirming, destination);

	return (
		<div className="flex min-h-[100dvh] items-center justify-center p-4">
			{/* This page IS the failure message: announce it on arrival, and title it as the page's h1. */}
			<Card className="w-full max-w-md text-center" role="alert">
				<CardHeader>
					{/* The page's only heading, so it must be an h1 — CardTitle renders a div, which would
					    leave this page with no heading at all for screen-reader navigation. */}
					<h1 data-slot="card-title" className="text-2xl leading-snug font-medium">
						{title}
					</h1>
					<CardDescription>{description}</CardDescription>
				</CardHeader>
				<CardContent>
					{recovery && isAuthenticated ? (
						// Signing in through /login would bounce straight back to the workspace: this session
						// is valid, only too old to link with.
						<Button className="w-full" onClick={() => setConfirming(true)}>
							Confirm access
						</Button>
					) : (
						<Link
							to="/login"
							search={recovery ? { returnTo: destination } : undefined}
							className={buttonVariants({ className: "w-full" })}
						>
							Back to sign in
						</Link>
					)}
				</CardContent>
			</Card>
			<ConfirmAccessDialog
				open={confirming}
				onOpenChange={setConfirming}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</div>
	);
}
