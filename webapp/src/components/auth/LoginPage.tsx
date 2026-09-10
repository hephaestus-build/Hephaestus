import { Link } from "@tanstack/react-router";

import { AuthSurface } from "@/components/auth/AuthSurface";
import { SignInButtons, type SignInButtonsProps } from "@/components/auth/SignInButtons";
import { SignInNotice } from "@/components/auth/SignInNotice";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { HephIcon } from "@/components/brand/HephIcon";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

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
 * link, a reload, and the return trip from a provider.
 */
export function LoginPage({ title = "Sign in to Hephaestus", error, ...signIn }: LoginPageProps) {
	const errorCopy = error ? (ERROR_COPY.get(error) ?? GENERIC_ERROR) : undefined;

	return (
		<AuthSurface className="grid lg:grid-cols-2">
			<div className="flex flex-col gap-8 p-6 md:p-10">
				<Link
					to="/"
					aria-label="Hephaestus home"
					className="flex w-fit items-center gap-2 font-medium hover:opacity-80"
				>
					<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />
				</Link>

				<div className="flex flex-1 items-center justify-center">
					<div className="flex w-full max-w-sm flex-col gap-6">
						<h1 className="text-3xl font-semibold tracking-tight text-balance">{title}</h1>
						<div aria-live="assertive" aria-atomic="true">
							{errorCopy ? (
								<Alert variant="destructive">
									<AlertTitle>{errorCopy.title}</AlertTitle>
									<AlertDescription>{errorCopy.description}</AlertDescription>
								</Alert>
							) : null}
						</div>
						<SignInButtons {...signIn} />
						<SignInNotice />
					</div>
				</div>
			</div>

			<BrandAside />
		</AuthSurface>
	);
}

/**
 * Nothing here may describe what happens after sign-in. This screen cannot tell a first-time visitor
 * from someone whose session expired, and only the first of those is sent through onboarding — so a
 * "what happens next" list is a claim that is wrong for most of the people reading it.
 */
function BrandAside() {
	return (
		<aside className="hidden flex-col items-start justify-center gap-6 border-l border-border p-10 lg:flex">
			<HephIcon size={88} pad={8} strokeWidth={1.4} />
			<p className="max-w-md text-lg leading-relaxed text-pretty">
				<span className="font-semibold text-foreground">
					Hephaestus reads the work a team already does
				</span>{" "}
				<span className="text-muted-foreground">
					and gives every developer practice feedback on it, where the work happens.
				</span>
			</p>
		</aside>
	);
}
