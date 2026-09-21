import type { ReactElement, ReactNode } from "react";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

import type { StatusDef } from "./status-def";
import { StatusBadge } from "./StatusBadge";

export interface StatusTooltipProps {
	/** The registry entry the trigger stands for; the tooltip says its words and its sentence. */
	def: Pick<StatusDef, "label" | "description">;
	/**
	 * The trigger element. A `<button>` when the icon stands alone, so a keyboard reaches the
	 * sentence; a `<span>` when it sits inside a control that is already a button, where the
	 * words are visible beside it and the sentence is the pointer's bonus.
	 */
	render: ReactElement;
	className?: string;
	/** What the trigger shows; left out when the rendered element draws itself, as a badge does. */
	children?: ReactNode;
}

/**
 * The registry's label and one-line description over a status icon, so an icon that only its
 * colour and shape distinguish explains itself on hover or focus.
 */
export function StatusTooltip({ def, render, className, children }: StatusTooltipProps) {
	return (
		<Tooltip>
			<TooltipTrigger render={render} className={className}>
				{children}
			</TooltipTrigger>
			<TooltipContent className="max-w-xs">
				<span className="font-semibold">{def.label}</span> · {def.description}
			</TooltipContent>
		</Tooltip>
	);
}

/**
 * A registry entry as its badge, with the registry's sentence on hover or focus — the shape
 * `StandingBadge` gives one enum, for any status the review console shows without its sentence
 * beside it. A button, so a keyboard reaches the sentence too; a `<span />` where the badge sits
 * inside a control that is already a button, so the two never nest. The button does nothing but
 * hold the tooltip, and says so with `data-tooltip-only`, so a surface whose whole row is a
 * control treats a press on it as the row's (`PracticeTableRow`).
 */
export interface StatusBadgeWithSentenceProps {
	def: StatusDef;
	/** A `<span />` where the badge sits inside a control that is already a button. */
	render?: ReactElement;
	className?: string;
}

export function StatusBadgeWithSentence({
	def,
	render = <button type="button" />,
	className,
}: StatusBadgeWithSentenceProps) {
	return (
		<StatusTooltip
			def={def}
			render={
				<StatusBadge
					def={def}
					render={render}
					data-tooltip-only=""
					className={cn("cursor-help", className)}
				/>
			}
		/>
	);
}
