import { cn } from "@/lib/utils";

/**
 * The `auth` surface renders no site footer, so sign-in and onboarding carry the imprint and privacy
 * links themselves. Both open in a new tab: leaving mid-flow would drop an unsaved consent choice.
 */
export function LegalLinks({ className }: { className?: string }) {
	return (
		<nav
			aria-label="Legal"
			className={cn(
				"flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground",
				className,
			)}
		>
			<LegalLink href="/privacy">Privacy notice</LegalLink>
			<span aria-hidden="true">·</span>
			<LegalLink href="/imprint">Imprint</LegalLink>
		</nav>
	);
}

export function LegalLink({ href, children }: { href: string; children: string }) {
	return (
		<a
			href={href}
			target="_blank"
			rel="noreferrer"
			className="underline underline-offset-4 hover:text-foreground"
		>
			{children}
			<span className="sr-only"> (opens in a new tab)</span>
		</a>
	);
}
