import { Link } from "@tanstack/react-router";

import { SignInButtons, type SignInButtonsProps } from "@/components/auth/SignInButtons";
import { SignInNotice } from "@/components/auth/SignInNotice";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { HephIcon } from "@/components/brand/HephIcon";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

// Never display raw OAuth error parameters; they can contain provider details.
const ERROR_COPY: Record<string, { title: string; description: string }> = {
	access_denied: {
		title: "Sign-in was cancelled",
		description: "No problem — you can try again whenever you're ready.",
	},
	idp_unavailable: {
		title: "That provider isn't responding",
		description: "We couldn't reach it just now. Give it a moment and try again.",
	},
};

function describeError(code: string): { title: string; description: string } {
	return (
		(Object.hasOwn(ERROR_COPY, code) ? ERROR_COPY[code] : undefined) ?? {
			title: "Something went wrong",
			description: "We couldn't sign you in. Please try again.",
		}
	);
}

/**
 * The standalone sign-in surface, for the visitors the dialog cannot serve: a shared or bookmarked
 * link, a reload, and the return trip from a provider. The dialog borrows its context from the page
 * behind it, so this page supplies its own.
 */
export function LoginPage({
	title = "Sign in to Hephaestus",
	error,
	...signIn
}: SignInButtonsProps & { title?: string; error?: string }) {
	const errorCopy = error ? describeError(error) : undefined;

	return (
		<div className="grid min-h-svh lg:grid-cols-2">
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
						<h1 className="text-2xl font-semibold tracking-tight text-balance">{title}</h1>
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
		</div>
	);
}

/**
 * Nothing here may describe what happens after sign-in. This screen cannot tell a first-time visitor
 * from someone whose session expired, and only the first of those is sent through onboarding — so a
 * "what happens next" list is a claim that is wrong for most of the people reading it.
 *
 * Dropped below `lg`, where the form needs the width. It is the landing page's own lede, so arriving
 * at sign-in first is told the same thing.
 */
function BrandAside() {
	return (
		<aside className="hidden flex-col items-start justify-center gap-6 border-l border-border bg-muted/40 p-10 lg:flex">
			<HephIcon size={88} pad={8} strokeWidth={1.4} />
			<p className="max-w-md text-lg leading-relaxed text-pretty">
				<span className="font-semibold text-foreground">
					The mentoring feedback a senior would give.
				</span>{" "}
				<span className="text-muted-foreground">
					For everyone, not only the people they have time for.
				</span>
			</p>
		</aside>
	);
}
