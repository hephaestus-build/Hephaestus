import type { LucideIcon } from "lucide-react";
import type { ReactElement, ReactNode } from "react";

import { cn } from "cn";

import { InlineLink } from "@/components/common/InlineLink";

import { OVERVIEW_TYPE } from "./overview-type-scale";

export interface CardFigure {
	key: string;
	/** A short label: "waiting", "finished". */
	term: ReactNode;
	/** The figure, a number or a duration; a link when it opens the rows it counts. */
	value: ReactNode;
	/** Nothing of it: the figure is drawn quiet. */
	none?: boolean;
	/**
	 * Opens the rows the figure counts. Number and term are then one link, underlined together as
	 * the outcome counts are ("2 complete"), rather than a bare number beside its word. A `label`
	 * starts with the visible words and adds only what they leave out (WCAG 2.5.3).
	 */
	link?: { render: ReactElement; label?: string };
}

/**
 * Figures in one line, each its number then its label, as the outcome tiles count: "2 running",
 * "272 finished". The row wraps where the card is narrow and takes no more room than its words, so a
 * card reads at a glance. The number black, the label muted, a figure
 * with nothing in it muted and regular.
 */
export function FigureRow({ label, figures }: { label: string; figures: readonly CardFigure[] }) {
	if (figures.length === 0) {
		return null;
	}
	return (
		<ul
			aria-label={label}
			className={cn("flex flex-wrap items-baseline gap-x-4 gap-y-1", OVERVIEW_TYPE.label)}
		>
			{figures.map((figure) => (
				<li key={figure.key} data-figure={figure.key} className="whitespace-nowrap">
					{figure.link === undefined ? (
						<>
							{/* A count link is muted at rest; here the number keeps the figure's colour. */}
							<span
								data-figure-value
								className={cn(
									"[&_a]:text-current",
									figure.none === true ? OVERVIEW_TYPE.none : OVERVIEW_TYPE.value,
								)}
							>
								{figure.value}
							</span>{" "}
							{figure.term}
						</>
					) : (
						<InlineLink tone="count" aria-label={figure.link.label} render={figure.link.render}>
							<span data-figure-value className={OVERVIEW_TYPE.value}>
								{figure.value}
							</span>{" "}
							{figure.term}
						</InlineLink>
					)}
				</li>
			))}
		</ul>
	);
}

/**
 * The title of one part of a card, in the size and weight of a card's title, with its icon and its
 * (i): "Now", "Last 30 days". A card of two parts carries one in each, so it reads as the cards
 * beside it.
 */
export function FigureCaption({
	icon: Icon,
	info,
	children,
}: {
	icon: LucideIcon;
	/** The part's (i). */
	info?: ReactNode;
	children: ReactNode;
}) {
	return (
		<div className="flex items-center gap-2 text-sm font-medium">
			<Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
			<p>{children}</p>
			{info}
		</div>
	);
}
