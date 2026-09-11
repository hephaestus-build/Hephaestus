import { ArrowDownIcon, ArrowUpIcon, ChevronsUpDownIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";

export interface SortButtonProps {
	/** `false` when this column is not the sorted one. */
	sorted: "asc" | "desc" | false;
	onToggle: () => void;
	/** Puts the icon before the label, for a right-aligned column. */
	reverse?: boolean;
	children: ReactNode;
}

/**
 * The clickable label in a sortable column header. The icon carries the direction because
 * `aria-sort`, which belongs on the surrounding `<th>`, is invisible to everyone who can see.
 *
 * Presentational on purpose: the two sorting models in this app — a TanStack column and a
 * hand-rolled sort key — both reduce to a direction and a toggle, and the control should not
 * look different depending on which one drives it.
 */
export function SortButton({ sorted, onToggle, reverse = false, children }: SortButtonProps) {
	const SortIcon = sorted ? (sorted === "asc" ? ArrowUpIcon : ArrowDownIcon) : ChevronsUpDownIcon;

	return (
		<button
			type="button"
			onClick={onToggle}
			className={cn(
				"group inline-flex items-center gap-1 rounded-sm outline-none hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50",
				sorted ? "text-foreground" : "text-muted-foreground",
				reverse && "flex-row-reverse",
			)}
		>
			{children}
			<SortIcon
				className={cn(
					"size-3.5 shrink-0",
					sorted ? "opacity-100" : "opacity-40 group-hover:opacity-70",
				)}
				aria-hidden
			/>
		</button>
	);
}
