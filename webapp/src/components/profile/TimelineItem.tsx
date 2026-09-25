import type { ReactNode } from "react";

import { cn } from "cn";

export interface TimelineItemProps {
	/** When this happened; carried as machine-readable `<time>` metadata when given. */
	at?: Date;
	/**
	 * What the date column shows — an absolute date, a relative phrase, anything. Omit it when
	 * the row above already names the same moment: the row then joins that group, keeping the
	 * rail but drawing no dot of its own.
	 */
	label?: ReactNode;
	/**
	 * On the last row only: keep the rail running past the content as a dashed line, saying
	 * "there is more below the fold". Collapsed lists set it; expanded lists end plain.
	 */
	tailContinues?: boolean;
	children: ReactNode;
}

/**
 * One row on a vertical timeline: label column, dot on a connecting rail, then the content.
 * The rail hides its tail on the last item; place rows inside a plain `<ol>`. The rail and its
 * dots are structure, in the grey the practice surfaces' palette gives them, so the accent stays
 * with the one control per surface.
 */
export function TimelineItem({ at, label, tailContinues = false, children }: TimelineItemProps) {
	const labelClassName =
		"col-start-2 mb-1 flex w-fit gap-1 text-xs text-muted-foreground sm:col-start-1 sm:row-start-1 sm:mt-3 sm:flex-col sm:items-end sm:text-right";
	return (
		<li className="group grid min-w-0 grid-cols-[1rem_minmax(0,1fr)] gap-x-3 sm:grid-cols-[4.5rem_1rem_minmax(0,1fr)]">
			{label !== undefined &&
				(at ? (
					<time dateTime={at.toISOString()} className={labelClassName}>
						{label}
					</time>
				) : (
					<span className={labelClassName}>{label}</span>
				))}
			<div className="relative col-start-1 row-start-1 row-end-3 sm:col-start-2">
				{label !== undefined && (
					<span
						className="absolute top-3 left-1/2 z-10 size-2.5 -translate-x-1/2 rounded-full border-2 border-background bg-muted-foreground"
						aria-hidden
					/>
				)}
				{tailContinues ? (
					// A dashed rail needs a border — a background strip cannot dash.
					<span
						className={cn(
							"absolute bottom-0 left-1/2 -translate-x-1/2 border-l border-dashed border-muted-foreground/50",
							label === undefined ? "top-0" : "top-5",
						)}
						aria-hidden
					/>
				) : (
					<span
						className={cn(
							"absolute bottom-0 left-1/2 w-px -translate-x-1/2 bg-border group-last:hidden",
							label === undefined ? "top-0" : "top-5",
						)}
						aria-hidden
					/>
				)}
			</div>
			<div className="col-start-2 mb-3 min-w-0 sm:col-start-3 sm:row-start-1">{children}</div>
		</li>
	);
}
