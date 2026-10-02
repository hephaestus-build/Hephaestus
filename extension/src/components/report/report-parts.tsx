import { type ReactNode, useState } from "react";

import { cn } from "cn";
import { Button } from "~/components/common/Button";
import { Skeleton } from "~/components/common/Skeleton";

/**
 * The report's second level, as in the provider's merge request reports: indented to line up with the
 * line's text (past its status icon) once the report is wide enough for that to cost nothing. Every
 * width here is the report's own (a container query): in the page that is the frame's width, and a
 * story in a narrow box shows the same thing.
 */
export const INDENT = "px-4 @lg:pl-12";

/** Rows a list shows before the reader asks for the rest of the page. */
export const INITIAL_ROWS = 5;

/** A part of the report that could not load, with its recovery. */
export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
	return (
		<div
			role="alert"
			className={cn(INDENT, "flex flex-wrap items-center gap-x-3 gap-y-1.5 py-2 text-sm")}
		>
			<p className="min-w-0 flex-1 basis-48 text-muted-foreground">{message}</p>
			<Button variant="outline" size="sm" onClick={onRetry}>
				Try again
			</Button>
		</div>
	);
}

export function ListSkeleton() {
	return (
		<div className={cn(INDENT, "flex flex-col gap-2 py-2")} aria-hidden>
			<Skeleton className="h-4 w-2/3" />
			<Skeleton className="h-4 w-1/2" />
		</div>
	);
}

/**
 * A list that shows its first rows and the rest of what it holds on request — the one truncation the
 * report has. Nothing here opens into more detail; that is the web app's.
 */
export function Truncated<T>({
	items,
	render,
}: {
	items: readonly T[];
	render: (item: T) => ReactNode;
}) {
	const [all, setAll] = useState(false);
	const hidden = all ? 0 : items.length - INITIAL_ROWS;
	return (
		<>
			<ul className="flex flex-col">
				{(hidden > 0 ? items.slice(0, INITIAL_ROWS) : items).map(render)}
			</ul>
			{hidden > 0 ? (
				<div className={cn(INDENT, "pb-1")}>
					<Button variant="ghost" size="sm" onClick={() => setAll(true)}>
						Show {hidden} more
					</Button>
				</div>
			) : null}
		</>
	);
}
