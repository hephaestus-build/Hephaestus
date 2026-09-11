import { Link } from "@tanstack/react-router";

import { SignInButtons, type SignInButtonsProps } from "@/components/auth/SignInButtons";
import { SignInNotice } from "@/components/auth/SignInNotice";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent, CardHeader } from "@/components/ui/card";

// Never display raw OAuth error parameters; they can contain provider details. A `Map` rather than
// an object literal, so a code of `__proto__` cannot reach an inherited value.
const ERROR_COPY = new Map([
	[
		"access_denied",
		{
			title: "Sign-in was cancelled",
			description: "No problem — you can try again whenever you're ready.",
		},
	],
	[
		"idp_unavailable",
		{
			title: "That provider isn't responding",
			description: "We couldn't reach it just now. Give it a moment and try again.",
		},
	],
]);

const GENERIC_ERROR = {
	title: "Something went wrong",
	description: "We couldn't sign you in. Please try again.",
};

export interface LoginPageProps extends SignInButtonsProps {
	title?: string;
	error?: string;
}

/**
 * The standalone sign-in surface, for the visitors the dialog cannot serve: a shared or bookmarked
 * link, a reload, and the return trip from a provider. All of them came here to pick a provider, so
 * the page holds nothing else.
 */
export function LoginPage({ title = "Sign in to Hephaestus", error, ...signIn }: LoginPageProps) {
	const errorCopy = error ? (ERROR_COPY.get(error) ?? GENERIC_ERROR) : undefined;

	return (
		<div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-background p-6">
			<Link
				to="/"
				aria-label="Hephaestus home"
				className="flex items-center gap-2 font-medium hover:opacity-80"
			>
				<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />
			</Link>

			<Card className="w-full max-w-sm">
				{/* No `HephIcon` here: the brand mark a few pixels above already is Heph's face. */}
				<CardHeader>
					<h1 className="text-xl font-semibold tracking-tight">{title}</h1>
				</CardHeader>
				{/* Margins rather than `gap` or `space-y`: the live region has to exist before an error
				    lands in it or nothing is announced, and as a flex or space-y sibling its empty box
				    would still claim a slot and push the buttons down on every ordinary sign-in. */}
				<CardContent>
					<div aria-live="assertive" aria-atomic="true" className="not-empty:mb-4">
						{errorCopy ? (
							<Alert variant="destructive">
								<AlertTitle>{errorCopy.title}</AlertTitle>
								<AlertDescription>{errorCopy.description}</AlertDescription>
							</Alert>
						) : null}
					</div>
					<SignInButtons {...signIn} />
					<SignInNotice className="mt-4" />
				</CardContent>
			</Card>
		</div>
	);
}
