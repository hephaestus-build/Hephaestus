import type { ReactNode } from "react";

import { PURSE_DEFS, type Purse } from "@/components/practice-vocabulary/purse-defs";

/** A purse's icon and name, over its columns in a usage table. The icon is untoned: a purse is not a state. */
export function PurseHeading({ purse }: { purse: Purse }) {
	const { icon: Icon, label } = PURSE_DEFS[purse];
	return (
		<span className="inline-flex items-center gap-1.5">
			<Icon className="size-3.5 shrink-0" aria-hidden />
			{label}
		</span>
	);
}

/**
 * A column label under a purse. Both purses repeat it, so a screen reader hears whose figure it is
 * after the visible words, which speech control still matches (WCAG SC 2.5.3).
 */
export function PurseLeaf({ purse, children }: { purse: Purse; children: ReactNode }) {
	return (
		<>
			{children} <span className="sr-only">({PURSE_DEFS[purse].label})</span>
		</>
	);
}
