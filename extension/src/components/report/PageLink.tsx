import type { ReactNode } from "react";

import { cn } from "cn";
import { linksWithin } from "~/shared/link-target";

export interface PageLinkProps {
	href: string;
	/** The provider page's origin; a link anywhere else renders as plain text. */
	allowedOrigin: string;
	children: ReactNode;
	className?: string;
}

/**
 * A link that takes the provider's own page to one of its addresses — a comment, the work itself —
 * rather than opening a new tab. Only an address on the page's own origin becomes a link; the worker
 * has already checked it leads to this work's pages.
 */
export function PageLink({ href, allowedOrigin, children, className }: PageLinkProps) {
	if (!linksWithin(href, allowedOrigin)) {
		return <span className={className}>{children}</span>;
	}
	return (
		<a
			href={href}
			target="_top"
			className={cn(
				"text-link rounded-sm font-medium underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50",
				className,
			)}
		>
			{children}
		</a>
	);
}
