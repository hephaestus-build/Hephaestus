import type * as React from "react";

import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

import type { StatusDef } from "./status-def";

interface StatusBadgeOwnProps {
	def: StatusDef;
}

export type StatusBadgeProps = StatusBadgeOwnProps &
	Omit<React.ComponentProps<typeof Badge>, "variant" | keyof StatusBadgeOwnProps>;

/**
 * The one badge every status registry renders through. It takes a whole registry entry so
 * the words, the colour and the icon travel together and cannot be recombined at a call site; a
 * per-enum wrapper exists only to pick the entry, never to re-decide how a badge looks.
 */
export function StatusBadge({ def, className, ...props }: StatusBadgeProps) {
	const { icon: Icon, label, badgeVariant } = def;
	return (
		<Badge variant={badgeVariant} className={cn("max-w-full", className)} {...props}>
			<Icon aria-hidden />
			<span className="truncate">{label}</span>
		</Badge>
	);
}
