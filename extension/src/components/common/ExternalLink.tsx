import { ExternalLinkIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { type ButtonSize, type ButtonVariant, buttonClasses } from "~/components/common/Button";

export interface ExternalLinkProps {
	href: string;
	/** The only origin this link may open; anything else renders as plain text. */
	allowedOrigin: string;
	children: ReactNode;
	/** Drawn as a button, for a link that is the reader's next step. */
	button?: { variant?: ButtonVariant; size?: ButtonSize };
	/** `link` takes the surface's link colour, as links in a provider's page do. */
	tone?: "text" | "link";
	className?: string;
}

/**
 * A link out of the extension, opened in a new tab. The address is checked against the one origin
 * it is allowed to reach, so a `javascript:` URL or another site can never become a live link.
 */
export function ExternalLink({
	href,
	allowedOrigin,
	children,
	button,
	tone = "text",
	className,
}: ExternalLinkProps) {
	let safe = false;
	try {
		const url = new URL(href);
		safe = url.origin === allowedOrigin && (url.protocol === "https:" || url.protocol === "http:");
	} catch {
		safe = false;
	}
	if (!safe) {
		return <span className={className}>{children}</span>;
	}
	return (
		<a
			href={href}
			target="_blank"
			rel="noreferrer noopener"
			data-variant={button === undefined ? undefined : (button.variant ?? "outline")}
			className={cn(
				button === undefined
					? cn(
							"inline-flex items-center gap-1 rounded-sm font-medium underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50",
							tone === "link" ? "text-link" : "hover:text-mentor",
						)
					: buttonClasses(button.variant ?? "outline", button.size),
				className,
			)}
		>
			{children}
			<ExternalLinkIcon aria-hidden className="size-3.5 opacity-70" />
			<span className="sr-only">(opens in a new tab)</span>
		</a>
	);
}
