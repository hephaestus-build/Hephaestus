import type { ComponentProps, ReactNode } from "react";

import { cn } from "cn";
import { INLINE_LINK_CLASS, OutboundMark } from "@/components/common/outbound-link";
import type { DocKey } from "@/lib/doc-targets";
import { docUrl } from "@/lib/docs-url";

export interface DocLinkProps extends Omit<
	ComponentProps<"a">,
	"href" | "target" | "rel" | "aria-label" | "children"
> {
	/** The page or section to open, by its name in `@/lib/doc-targets`. */
	doc: DocKey;
	/** Words that name what the reader finds there, such as "Connect Slack". Never "Learn more" alone. */
	children: ReactNode;
}

/**
 * A link from running text to the docs (`webapp/DESIGN.md` § Docs links). It always opens in a new
 * tab and says so with the outbound icon and to a screen reader. It has no `target`, `rel` or
 * `aria-label` of its own, so a caller cannot break that promise or replace the spoken warning.
 *
 * It is underlined at rest. It sits in muted text, where the colour of a link alone does not mark
 * it in dark mode (WCAG 2.2 SC 1.4.1).
 */
export function DocLink({ doc, className, children, ...props }: DocLinkProps) {
	return (
		<a
			{...props}
			href={docUrl(doc)}
			target="_blank"
			rel="noopener noreferrer"
			className={cn(
				"text-foreground underline decoration-muted-foreground/60 hover:decoration-current focus-visible:decoration-current",
				INLINE_LINK_CLASS,
				className,
			)}
		>
			{children}
			<OutboundMark />
		</a>
	);
}
