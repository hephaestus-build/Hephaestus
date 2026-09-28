import type { ReactNode } from "react";

/**
 * A `<dl>` rather than prose, because these are labelled values and a screen reader should announce
 * them as pairs. No box around it: the facts are read the way Activity's figures are, a muted label
 * over its value, and the columns come from the level's width rather than the viewport's.
 */
export function ReviewFactGrid({ children }: { children: ReactNode }) {
	return (
		<div className="@container">
			<dl className="grid gap-x-6 gap-y-4 @lg:grid-cols-2 @3xl:grid-cols-3">{children}</dl>
		</div>
	);
}

export function ReviewFact({ label, children }: { label: string; children: ReactNode }) {
	return (
		<div className="min-w-0 space-y-0.5">
			<dt className="text-xs text-muted-foreground">{label}</dt>
			<dd className="min-w-0 text-sm">{children}</dd>
		</div>
	);
}
