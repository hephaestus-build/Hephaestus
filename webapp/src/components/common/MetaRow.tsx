import type { ReactNode } from "react";

import { cn } from "cn";

export interface MetaRowProps {
	captions?: ReactNode[];
	/** Chips reporting a state. Rendered after the captions, at their own rhythm. */
	badges?: ReactNode;
	className?: string;
}

/**
 * The line of facts under a row's title.
 *
 * Rendered as one flat run at one gap, captions and chips read as a single run-on sentence.
 * Separators between the captions and a wider gap before the chips say which is which without
 * adding a word.
 */
export function MetaRow({ captions = [], badges, className }: MetaRowProps) {
	const shown = captions.filter(Boolean);
	return (
		<span className={cn("flex flex-wrap items-center gap-x-3 gap-y-1.5", className)}>
			{shown.length > 0 && (
				<span className="flex flex-wrap items-center gap-x-2 gap-y-1.5">
					{shown.map((caption, index) => (
						<span key={index} className="flex items-center gap-x-2">
							{index > 0 && (
								<span aria-hidden className="text-muted-foreground/50">
									·
								</span>
							)}
							{caption}
						</span>
					))}
				</span>
			)}
			{badges && <span className="flex flex-wrap items-center gap-1.5">{badges}</span>}
		</span>
	);
}
