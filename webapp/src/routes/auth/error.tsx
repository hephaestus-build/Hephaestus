import { createFileRoute, Link } from "@tanstack/react-router";

import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";

interface ErrorSearch {
	code?: string;
}

/** PII-free, friendly copy keyed by the server's auth-failure codes. */
const ERROR_COPY: Record<string, { title: string; description: string }> = {
	oauth_failure: {
		title: "Sign-in didn't complete",
		description:
			"We couldn't finish signing you in with that provider. Please try again from the sign-in page.",
	},
	token_exchange: {
		title: "Sign-in couldn't be verified",
		description:
			"There was a problem confirming your identity with the provider. Please try signing in again.",
	},
	idp_unavailable: {
		title: "Provider unavailable",
		description:
			"The identity provider couldn't be reached right now. Please try again in a few moments.",
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
			"Linking an identity needs a recent sign-in. Sign in again with an identity already linked to your account, then link from Settings.",
	},
	unknown_provider: {
		title: "Provider is not configured",
		description:
			"This Hephaestus instance does not have that sign-in provider configured. Ask an admin to check the login provider settings.",
	},
};

function describe(code: string | undefined): { title: string; description: string } {
	if (code && ERROR_COPY[code]) {
		return ERROR_COPY[code];
	}
	return {
		title: "Something went wrong",
		description: "We hit an unexpected problem signing you in. Please try again.",
	};
}

export const Route = createFileRoute("/auth/error")({
	staticData: { surface: "auth" },
	validateSearch: (search): ErrorSearch => ({
		code: typeof search.code === "string" ? search.code : undefined,
	}),
	component: AuthErrorPage,
});

function AuthErrorPage() {
	const { code } = Route.useSearch();
	const { title, description } = describe(code);

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
					<Link to="/login" className={buttonVariants({ className: "w-full" })}>
						Back to sign in
					</Link>
				</CardContent>
			</Card>
		</div>
	);
}
