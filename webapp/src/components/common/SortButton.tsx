import { ArrowDownIcon, ArrowUpIcon, ChevronsUpDownIcon } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";

import { cn } from "cn";
import { FOCUS_RING } from "@/components/common/focus";

export interface SortButtonProps extends Omit<ComponentProps<"button">, "type"> {
	/** `false` when this column is not the sorted one. */
	sorted: "asc" | "desc" | false;
	onToggle: () => void;
	/** Puts the icon before the label, for a right-aligned column. */
	reverse?: boolean;
	children: ReactNode;
}

const SORT_ICONS = {
	none: ChevronsUpDownIcon,
	asc: ArrowUpIcon,
	desc: ArrowDownIcon,
};

/**
 * The clickable label in a sortable column header. The icon carries the direction because
 * `aria-sort`, which belongs on the surrounding `<th>`, is invisible to everyone who can see.
 * An unsorted column keeps a faint two-way chevron, so every sortable header reads as one at
 * rest, and the sorted column's arrow is the one accent on the header row.
 *
 * Presentational on purpose: the two sorting models in this app — a TanStack column and a
 * hand-rolled sort key — both reduce to a direction and a toggle, and the control should not
 * look different depending on which one drives it.
 *
 * The remaining button props and the `ref` reach the `<button>`, so a header can hand this to a
 * `TooltipTrigger render=` slot and keep the sort on click and the legend on hover.
 */
export function SortButton({
	sorted,
	onToggle,
	reverse = false,
	children,
	className,
	onClick,
	...props
}: SortButtonProps) {
	const SortIcon = SORT_ICONS[sorted === false ? "none" : sorted];

	return (
		<button
			type="button"
			{...props}
			onClick={(event) => {
				onClick?.(event);
				onToggle();
			}}
			className={cn(
				"group inline-flex items-center gap-1 rounded-sm hover:text-foreground",
				FOCUS_RING,
				sorted === false ? "text-muted-foreground" : "text-foreground",
				reverse && "flex-row-reverse",
				className,
			)}
		>
			{children}
			<SortIcon
				className={cn(
					"size-3.5 shrink-0",
					sorted === false ? "opacity-40 group-hover:opacity-70" : "text-mentor",
				)}
				aria-hidden
			/>
		</button>
	);
}
