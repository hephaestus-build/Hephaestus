import { Link } from "@tanstack/react-router";

import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { hasText } from "@/lib/text";

const ALREADY_CONNECTED = {
	title: "Account already connected",
	description:
		"That provider identity is already connected to another account. Sign in with the original account instead.",
};

/**
 * PII-free copy keyed by the server's auth-failure codes. A `Map` rather than an object literal, so
 * a code such as `toString` or `__proto__` cannot reach an inherited value.
 */
const ERROR_COPY = new Map<string, { title: string; description: string }>([
	[
		"oauth_failure",
		{
			title: "Sign-in did not complete",
			description:
				"We could not finish signing you in with that provider. Try again from the sign-in page.",
		},
	],
	[
		"token_exchange",
		{
			title: "Sign-in could not be verified",
			description: "We could not confirm your identity with the provider. Try signing in again.",
		},
	],
	[
		"idp_unavailable",
		{
			title: "Provider unavailable",
			description: "We could not reach the identity provider. Wait a moment, then try again.",
		},
	],
	["already_linked", ALREADY_CONNECTED],
	["identity_already_linked", ALREADY_CONNECTED],
	[
		"link_requires_auth",
		{
			// Slack and Outline are both link-only: they can only be attached to an existing session.
			title: "Sign in before connecting that account",
			description:
				"Open Hephaestus, sign in with GitHub or GitLab, then connect Slack or Outline from User settings.",
		},
	],
	[
		"step_up_required",
		{
			// The session is valid and the account is right; only its age is the problem, so this must
			// not read as a rejected identity.
			title: "Confirm access before connecting that account",
			description:
				"Connecting a sign-in account needs a recent sign-in. Confirm access with a sign-in account you already connected, then connect this one again.",
		},
	],
	[
		"client_not_registered",
		{
			// Reached inside the browser extension's sign-in window: the instance does not list that
			// extension, so the server refuses to hand it a sign-in rather than redirect to it.
			title: "This extension cannot sign in here",
			description:
				"This Hephaestus instance does not allow that browser extension to sign in. Ask an instance admin to add its extension ID, then try again from the extension.",
		},
	],
	[
		"unknown_provider",
		{
			title: "Provider is not configured",
			description:
				"This Hephaestus instance does not have that sign-in provider configured. Ask an instance admin to check the login provider settings.",
		},
	],
]);

const GENERIC_ERROR = {
	title: "We could not sign you in",
	description: "Try again. If it keeps failing, contact your instance operator.",
};

export interface AuthErrorPageProps {
	/** The server's auth-failure code. A missing or unknown code gets the generic message. */
	code?: string;
	/**
	 * The one next step. A session that is valid but too old to connect an account confirms access
	 * in place, because signing in through the sign-in page would bounce straight back.
	 */
	next: { kind: "confirm-access"; onConfirm: () => void } | { kind: "sign-in"; returnTo?: string };
}

/** Where the server sends a sign-in or account connection that it refused. */
export function AuthErrorPage({ code, next }: AuthErrorPageProps) {
	const { title, description } =
		(hasText(code) ? ERROR_COPY.get(code) : undefined) ?? GENERIC_ERROR;
	return (
		<div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-background p-6">
			<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />
			{/* This page is the failure message: announce it on arrival. */}
			<Card className="w-full max-w-sm" role="alert">
				<CardHeader>
					<h1 className="text-xl font-semibold tracking-tight">{title}</h1>
					<CardDescription>{description}</CardDescription>
				</CardHeader>
				<CardContent>
					{next.kind === "confirm-access" ? (
						<Button className="w-full" onClick={next.onConfirm}>
							Confirm access
						</Button>
					) : (
						<Link
							to="/login"
							search={next.returnTo === undefined ? undefined : { returnTo: next.returnTo }}
							className={buttonVariants({ className: "w-full" })}
						>
							Back to sign in
						</Link>
					)}
				</CardContent>
			</Card>
		</div>
	);
}
