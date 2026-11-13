import { cn } from "cn";
import { InlineLink, type InlineLinkProps } from "@/components/common/InlineLink";
import type { DocKey } from "@/lib/doc-targets";
import { docUrl } from "@/lib/docs-url";

export interface DocLinkProps extends Omit<
	InlineLinkProps,
	"href" | "external" | "onClick" | "tone" | "render" | "target" | "rel" | "aria-label"
> {
	/** The page or section to open, by its name in `@/lib/doc-targets`. */
	doc: DocKey;
	/** Words that name what the reader finds there, such as "Connect Slack". Never "Learn more" alone. */
	children: InlineLinkProps["children"];
}

/**
 * A link from running text to the docs (`webapp/DESIGN.md` § Docs links). It always opens in a new
 * tab and says so with the outbound icon and to a screen reader. It has no `target`, `rel` or
 * `aria-label` of its own, so a caller cannot break that promise or replace the spoken warning.
 *
 * It is underlined at rest. It sits in muted text, where the colour of `InlineLink` alone does not
 * mark it in dark mode (WCAG 2.2 SC 1.4.1).
 */
export function DocLink({ doc, className, ...props }: DocLinkProps) {
	return (
		<InlineLink
			href={docUrl(doc)}
			external
			className={cn(
				"underline decoration-muted-foreground/60 hover:decoration-current focus-visible:decoration-current",
				className,
			)}
			{...props}
		/>
	);
}
