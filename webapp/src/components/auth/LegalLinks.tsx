import { Link, type LinkProps } from "@tanstack/react-router";

import { cn } from "cn";

/** The `auth` surface renders no site footer, so sign-in and onboarding carry these themselves. */
export function LegalLinks({ className }: { className?: string }) {
	return (
		<nav
			aria-label="Legal"
			className={cn(
				"flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground",
				className,
			)}
		>
			<LegalLink to="/privacy">Privacy notice</LegalLink>
			<span aria-hidden="true">·</span>
			<LegalLink to="/imprint">Imprint</LegalLink>
		</nav>
	);
}

/** Opens in a new tab: leaving mid-flow would drop an unsaved consent answer. */
export function LegalLink({ to, children }: { to: LinkProps["to"]; children: string }) {
	return (
		<Link
			to={to}
			target="_blank"
			rel="noopener noreferrer"
			className="underline underline-offset-4 hover:text-foreground"
		>
			{children}
			<span className="sr-only"> (opens in a new tab)</span>
		</Link>
	);
}
