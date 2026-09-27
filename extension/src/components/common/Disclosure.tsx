import { ChevronRightIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";

export interface DisclosureProps {
	/** The always-visible line; it is the control, so it must say what opens. */
	summary: ReactNode;
	defaultOpen?: boolean;
	children: ReactNode;
	className?: string;
	/**
	 * `inline` sits in running content; `row` is a full-width row of a box, as a provider draws the
	 * rows of its own boxes: padded to the box's edge, with a hover ground.
	 */
	variant?: "inline" | "row";
}

const SUMMARY_CLASSES: Record<NonNullable<DisclosureProps["variant"]>, string> = {
	inline: "rounded-md py-1 text-sm font-medium focus-visible:ring-3 focus-visible:ring-ring/50",
	row: "px-4 py-2.5 text-sm font-medium hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset",
};

/**
 * Detail that most readers do not need first: native `<details>`, so the keyboard, the screen reader
 * and find-in-page work without script, and the content stays in the document when closed.
 */
export function Disclosure({
	summary,
	defaultOpen = false,
	children,
	className,
	variant = "inline",
}: DisclosureProps) {
	return (
		<details open={defaultOpen} className={cn("group", className)}>
			<summary
				className={cn(
					"flex cursor-pointer list-none items-center gap-1.5 outline-none select-none hover:text-foreground [&::-webkit-details-marker]:hidden",
					SUMMARY_CLASSES[variant],
				)}
			>
				<ChevronRightIcon
					aria-hidden
					className="size-4 shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none"
				/>
				{summary}
			</summary>
			<div className={variant === "row" ? "px-4 pt-1 pb-3" : "pt-2"}>{children}</div>
		</details>
	);
}
