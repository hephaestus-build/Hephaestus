import { mergeProps } from "@base-ui/react/merge-props";
import { useRender } from "@base-ui/react/use-render";
import { ExternalLinkIcon } from "lucide-react";
import type { ReactNode } from "react";

import { FOCUS_RING } from "@/components/common/focus";
import { cn } from "@/lib/utils";

export interface InlineLinkProps extends Omit<
	useRender.ComponentProps<"a">,
	"href" | "onClick" | "children"
> {
	/**
	 * An address makes it an `<a>`; with `onClick` instead it is a `<button>`; with neither, a
	 * `<span>` of plain text.
	 */
	href?: string;
	/** Another site: the link opens in a new tab, says so, and carries the outbound icon. */
	external?: boolean;
	onClick?: () => void;
	children: ReactNode;
}

/**
 * A link inside running text — a practice name, a work reference, a group, a crumb in a level's
 * path — and the one rule every inline link on a practice surface follows: plain text in the
 * text colour at rest, nothing dashed and nothing visible, and on hover or focus mentor blue
 * with a solid underline. The one component every such link renders through, so a name that
 * opens a level and one that opens the provider's page look alike. A reference with nowhere to
 * go wears none of it: a word that answers no press gets no hover. Rendered through
 * `useRender`, so it can stand in a `render=` slot such as a tooltip trigger's.
 */
export function InlineLink({
	href,
	external = false,
	onClick,
	className,
	children,
	render,
	...props
}: InlineLinkProps) {
	const defaultTagName = href ? "a" : onClick ? "button" : "span";
	// A `render=` slot is a control of its own (a router link, say), so it takes the hover and ring
	// too.
	const interactive = defaultTagName !== "span" || render !== undefined;
	// Only a real address opens elsewhere; a reference with no address is a word, icon and all.
	const outbound = href !== undefined && external;
	const ownProps: Record<string, unknown> = {
		className: cn(
			"text-foreground",
			interactive && [
				"cursor-pointer rounded-sm text-left decoration-1 underline-offset-3 hover:text-mentor hover:underline focus-visible:text-mentor focus-visible:underline",
				FOCUS_RING,
			],
			outbound && "inline-flex items-center gap-0.5",
			className,
		),
	};
	if (href) {
		ownProps.href = href;
		if (outbound) {
			ownProps.target = "_blank";
			ownProps.rel = "noopener noreferrer";
		}
	} else if (onClick) {
		ownProps.type = "button";
		ownProps.onClick = onClick;
	}
	return useRender({
		defaultTagName,
		render,
		props: mergeProps(ownProps, props, {
			children: outbound ? (
				<>
					{children}
					<ExternalLinkIcon className="size-3 shrink-0" aria-hidden />
					<span className="sr-only"> (opens in a new tab)</span>
				</>
			) : (
				children
			),
		}),
	});
}
