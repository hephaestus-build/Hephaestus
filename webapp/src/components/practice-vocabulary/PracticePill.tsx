import type { ComponentType } from "react";

import { cn } from "cn";
import { HIT_AREA_24 } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import { Badge } from "@/components/ui/badge";

/** A practice's group as `getGroupVisual` gives it: its icon, and its pill classes for the icon's color. */
export interface PracticeSquare {
	Icon: ComponentType<{ className?: string }>;
	pill: string;
}

export interface PracticePillProps {
	name: string;
	/** Opens the practice's level; without it the name is a word. */
	onOpen?: () => void;
	/**
	 * The practice's group, from `getGroupVisual`: its icon leads the name inside the pill, in the
	 * group's color. Left out where the surface does not know the group, and the pill holds the name
	 * alone rather than a stand-in mark that would read as a logo that failed to load.
	 */
	group?: PracticeSquare;
	/**
	 * `row` in a table, a list or a card's head; `sm` in a line of running text and in the weekly
	 * digest, where a practice stays the plain gray pill it has always been there.
	 */
	size?: "sm" | "row";
	className?: string;
}

/**
 * A practice named as the small gray pill with its group's icon inside it before the name,
 * everywhere a surface names one in a row, so a practice looks the same in every table, list and
 * card's head. The pill's words are the size of a group's name beside it (`text-sm`), so the two
 * read as one line. The icon is a mark, not a control: the name opens the practice when there is
 * somewhere to open it, and a keyboard reaches it.
 */
export function PracticePill({ name, onOpen, group, size = "row", className }: PracticePillProps) {
	if (size === "sm") {
		return <TextPracticePill name={name} onOpen={onOpen} className={className} />;
	}
	return (
		<span
			data-practice-pill=""
			className={cn(
				"inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-full bg-secondary px-2.5 py-0.5 text-sm font-medium text-secondary-foreground",
				className,
			)}
		>
			{group !== undefined && (
				// The group's color on the icon alone: the pill's gray stays the practice's ground.
				<group.Icon
					aria-hidden
					className={cn("size-3.5 shrink-0", group.pill, "bg-transparent dark:bg-transparent")}
				/>
			)}
			<InlineLink
				onClick={onOpen}
				className={cn("inline-flex max-w-full min-w-0", onOpen && HIT_AREA_24)}
			>
				<span className="truncate">{name}</span>
			</InlineLink>
		</span>
	);
}

/**
 * A practice in a line of running text: the plain gray pill, a button when there is somewhere to
 * open it, so the name is the control and a keyboard reaches it.
 */
function TextPracticePill({
	name,
	onOpen,
	className,
}: Pick<PracticePillProps, "name" | "onOpen" | "className">) {
	return (
		<Badge
			variant="secondary"
			render={onOpen ? <button type="button" onClick={onOpen} /> : undefined}
			className={cn("max-w-full", onOpen && [HIT_AREA_24, "cursor-pointer"], className)}
		>
			<span className="truncate">{name}</span>
		</Badge>
	);
}
