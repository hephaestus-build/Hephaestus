import type { ReactElement, ReactNode } from "react";

import { cn } from "cn";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

import { FOCUS_RING, HIT_AREA_24 } from "@/components/common/focus";
import { type StatusDef, statusToneClass } from "@/components/common/status-def";

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

export interface StatusIconProps {
	/** The registry entry the icon stands for: its glyph, in its tone, named by its label. */
	def: StatusDef;
	className?: string;
}

/**
 * A registry entry's icon standing alone as the tooltip's trigger: a button with no chrome of its
 * own, so a keyboard reaches the sentence, and named by the entry's label, since the glyph is all
 * that tells one value from another.
 */
export function StatusIcon({ def, className }: StatusIconProps) {
	const Icon = def.icon;
	return (
		<StatusTooltip
			def={def}
			render={<button type="button" aria-label={def.label} />}
			// The icon is 14 px, so the hit area is widened a step beyond the constant's.
			className={cn(
				HIT_AREA_24,
				"inline-flex cursor-help items-center before:-inset-1.5",
				FOCUS_RING,
				className,
			)}
		>
			<Icon className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))} aria-hidden />
		</StatusTooltip>
	);
}
