import type { ComponentType } from "react";

import { cn } from "cn";
import { InlineLink } from "@/components/common/InlineLink";
import { hasText } from "@/lib/text";

export interface GroupNameProps {
	name: string;
	icon: ComponentType<{ className?: string }>;
	/** The group's pill classes from `getGroupVisual`; without them the name is muted grey. */
	pill?: string;
	/** Opens the group's level; without it the name is a word. */
	onOpen?: () => void;
	className?: string;
}

/**
 * A group's icon before its name, both in the group's colour, with the name an `InlineLink` under
 * the one rule every link on the page follows. The pill's ground is dropped in both themes: this is
 * a name, not a badge. The feedback cards and the practices table name a group through it, so the
 * two read as one thing.
 */
export function GroupName({ name, icon: Icon, pill, onOpen, className }: GroupNameProps) {
	return (
		<span
			className={cn(
				"inline-flex items-center gap-1.5 font-medium",
				hasText(pill) ? [pill, "bg-transparent dark:bg-transparent"] : "text-muted-foreground",
				className,
			)}
		>
			<Icon className="size-3.5 shrink-0" aria-hidden />
			{/* `text-inherit` over the link's own colour: the name keeps the group's. */}
			<InlineLink onClick={onOpen} className="text-inherit">
				{name}
			</InlineLink>
		</span>
	);
}
