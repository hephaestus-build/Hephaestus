import { cn } from "cn";
import { HIT_AREA_24 } from "@/components/common/focus";
import { InlineLink } from "@/components/common/InlineLink";
import { Badge } from "@/components/ui/badge";

import type { GroupMark } from "./group-visuals";

export interface PracticePillProps {
	name: string;
	/** Opens the practice's level; without it the name is a word. */
	onOpen?: () => void;
	/**
	 * The practice's group: its icon leads the name inside the pill, in the group's color. Left out
	 * where the surface does not know the group, and the pill holds the name alone rather than a
	 * stand-in mark that would read as a logo that failed to load.
	 */
	group?: GroupMark;
	className?: string;
}

/**
 * A practice named in a row: the gray pill with its group's icon inside it before the name, so a
 * practice looks the same in every table, list and card's head. The pill's words are the size of a
 * group's name beside it (`text-sm`), so the two read as one line. The icon is a mark, not a
 * control: the name opens the practice when there is somewhere to open it, and a keyboard reaches
 * it.
 */
export function PracticePill({ name, onOpen, group, className }: PracticePillProps) {
	return (
		<Badge
			variant="secondary"
			size="lg"
			data-practice-pill=""
			className={cn("max-w-full min-w-0 whitespace-nowrap", className)}
		>
			{group !== undefined && <group.Icon aria-hidden className={group.ink} />}
			<InlineLink
				onClick={onOpen}
				className={cn("inline-flex max-w-full min-w-0", onOpen && HIT_AREA_24)}
			>
				<span className="truncate">{name}</span>
			</InlineLink>
		</Badge>
	);
}

/**
 * A practice in a line of running text and in the weekly digest: the plain gray pill, a button
 * when there is somewhere to open it, so the name is the control and a keyboard reaches it.
 */
export function PracticeTextPill({ name, onOpen, className }: Omit<PracticePillProps, "group">) {
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

/**
 * A practice's name as its level's heading: the row's gray pill at the heading's size, with its
 * group's icon before the words. Drawn as an inline span rather than a `Badge`, which is a box of one
 * line: inside the header's three-line limit the pill flows as text, so a long name wraps inside it.
 * The full name is its `title`, as the limit can cut it short.
 */
export function PracticeTitlePill({ name, group }: Pick<PracticePillProps, "name" | "group">) {
	return (
		<span
			title={name}
			className="rounded-2xl bg-secondary [box-decoration-break:clone] px-3.5 py-1 text-secondary-foreground"
		>
			{group !== undefined && (
				<group.Icon
					aria-hidden
					className={cn("mr-2 inline-block size-6 align-[-0.2em]", group.ink)}
				/>
			)}
			{name}
		</span>
	);
}
