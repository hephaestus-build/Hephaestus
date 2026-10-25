import type * as React from "react";

import { cn } from "cn";
import { Badge } from "@/components/ui/badge";

import type { StatusDef } from "./status-def";

interface StatusBadgeOwnProps {
	def: StatusDef;
	/** Lets a label whose words are the whole message wrap onto a second line instead of being cut. */
	wrap?: boolean;
}

export type StatusBadgeProps = StatusBadgeOwnProps &
	Omit<React.ComponentProps<typeof Badge>, "variant" | keyof StatusBadgeOwnProps>;

/**
 * The one badge every status registry renders through. It takes a whole registry entry so
 * the words, the colour and the icon travel together and cannot be recombined at a call site; a
 * per-enum wrapper exists only to pick the entry, never to re-decide how a badge looks.
 */
export function StatusBadge({ def, wrap = false, className, ...props }: StatusBadgeProps) {
	const { icon: Icon, label, badgeVariant } = def;
	return (
		<Badge
			variant={badgeVariant}
			className={cn("max-w-full", wrap && "h-auto whitespace-normal", className)}
			{...props}
		>
			<Icon aria-hidden />
			<span className={wrap ? "min-w-0" : "truncate"}>{label}</span>
		</Badge>
	);
}
