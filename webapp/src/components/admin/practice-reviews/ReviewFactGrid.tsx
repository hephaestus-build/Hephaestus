import type { ReactNode } from "react";

/** A `<dl>` rather than prose, because these are labelled values and a screen reader should announce
 * them as pairs. */
export function ReviewFactGrid({ children }: { children: ReactNode }) {
	return (
		<div className="@container">
			<dl className="grid gap-4 rounded-lg border p-4 @lg:grid-cols-2 @3xl:grid-cols-3">
				{children}
			</dl>
		</div>
	);
}

export function ReviewFact({ label, children }: { label: string; children: ReactNode }) {
	return (
		<div className="min-w-0 space-y-1">
			<dt className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{label}</dt>
			<dd className="min-w-0 text-sm">{children}</dd>
		</div>
	);
}
