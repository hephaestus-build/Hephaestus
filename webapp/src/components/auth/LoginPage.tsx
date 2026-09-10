import { Link } from "@tanstack/react-router";

import { SignInButtons, type SignInButtonsProps } from "@/components/auth/SignInButtons";
import { SignInNotice } from "@/components/auth/SignInNotice";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
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
						<div className="space-y-1.5">
							<h1 className="text-2xl font-semibold tracking-tight text-balance">{title}</h1>
							<p className="text-sm text-muted-foreground">
								Your first sign-in creates your account.
							</p>
						</div>
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

			<OnboardingAside />
		</div>
	);
}

const NEXT_STEPS = [
	{ title: "Sign in", detail: "Use the provider account you already have. No new password." },
	{ title: "See how your data is used", detail: "A short notice, then you accept the terms." },
	{
		title: "Choose whether to join the research",
		detail: "Optional, and you can change your mind later.",
	},
];

/** Only the wide layout has room for it, and everything it says is repeated during onboarding. */
function OnboardingAside() {
	return (
		<aside className="hidden flex-col justify-center gap-10 border-l border-border bg-muted/40 p-10 lg:flex">
			{/* The landing page's own lede, so arriving at sign-in first says the same thing. */}
			<p className="max-w-md text-lg leading-relaxed text-pretty">
				<span className="font-semibold text-foreground">
					The mentoring feedback a senior would give.
				</span>{" "}
				<span className="text-muted-foreground">
					For everyone, not only the people they have time for.
				</span>
			</p>

			<div className="max-w-md space-y-4">
				<h2 className="text-sm font-medium">What happens next</h2>
				<ol className="space-y-4">
					{NEXT_STEPS.map(({ title, detail }, index) => (
						<li key={title} className="flex gap-3 text-sm">
							<span
								aria-hidden="true"
								className="flex size-6 shrink-0 items-center justify-center rounded-full border border-muted-foreground/30 text-xs font-medium text-muted-foreground"
							>
								{index + 1}
							</span>
							<span>
								<span className="block font-medium">{title}</span>
								<span className="block text-muted-foreground">{detail}</span>
							</span>
						</li>
					))}
				</ol>
			</div>
		</aside>
	);
}
