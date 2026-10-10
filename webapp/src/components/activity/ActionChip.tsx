import type { ReactNode } from "react";

import { cn } from "cn";
import type { ActivityAction } from "@/api/types.gen";
import type { IconComponent } from "@/components/icons/provider-icons";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { capitalise } from "@/lib/text";

import { ACTIVITY_KIND_DEFS, actionPhrase, countPhrase } from "./activity-kind-defs";
import { ACTIVITY_TONES, type ActivityTone } from "./activity-tones";

/**
 * How a chip names what happened, by the room it has. `labelled` — a tile, a chart's legend — is
 * the icon, how often and what: "82 on code". `count` — a table cell, dense and headed by its
 * column — is the icon and how often. `work` is one piece of work's row, where a lifecycle event
 * reads by its label ("Merged") because it happens once, and a comment by its count because it
 * piles up. Each keeps the whole phrase in its tooltip and its accessible name.
 */
export type ActionChipDisplay = "count" | "labelled" | "work";

export interface ActionChipProps {
	action: ActivityAction;
	providerType: ProviderType;
	display: ActionChipDisplay;
}

/**
 * One kind of activity as an icon in its provider colour and a number or a label in the text colour:
 * the leaderboard's badge language, which GitHub and GitLab users read without the tooltip. The
 * tooltip and the accessible name spell it out — "3 comments on code" — so no chip is only a picture:
 * to assistive technology the chip is one image named by that phrase.
 */
export function ActionChip({ action, providerType, display }: ActionChipProps) {
	const def = ACTIVITY_KIND_DEFS[action.kind];
	const byLabel = display === "work" && def.chip === "label";
	const phrase = capitalise(
		byLabel
			? actionPhrase(action, providerType)
			: countPhrase(action.kind, action.count, providerType),
	);
	return (
		<CountChip icon={def.icon(providerType)} tone={def.tone} phrase={phrase}>
			<span
				aria-hidden
				className={cn(byLabel ? "text-muted-foreground" : "font-medium tabular-nums")}
			>
				{byLabel ? def.label : action.count}
			</span>
			{byLabel && action.count > 1 && (
				<span aria-hidden className="font-medium tabular-nums">
					×{action.count}
				</span>
			)}
			{display === "labelled" && (
				<span aria-hidden className="text-muted-foreground">
					{def.countLabel}
				</span>
			)}
		</CountChip>
	);
}

export interface CountChipProps {
	icon: IconComponent;
	tone: ActivityTone;
	/** The whole phrase, its tooltip and its accessible name: "12 pull requests reviewed". */
	phrase: string;
	/** What the chip shows after its icon, hidden from assistive technology, which reads `phrase`. */
	children: ReactNode;
}

/** An icon in its tone and what it counts, spoken as one phrase: every chip's shape. */
export function CountChip({ icon: Icon, tone, phrase, children }: CountChipProps) {
	return (
		<Tooltip>
			<TooltipTrigger
				render={<span role="img" aria-label={phrase} />}
				className="inline-flex items-center gap-1 text-sm whitespace-nowrap"
			>
				<Icon size={16} className={cn("shrink-0", ACTIVITY_TONES[tone].text)} />
				{children}
			</TooltipTrigger>
			<TooltipContent>{phrase}</TooltipContent>
		</Tooltip>
	);
}

/**
 * A count of nothing in a table: a muted dash, which a column of figures reads past, with the
 * phrase it stands for — "0 issues opened" — for a screen reader.
 */
export function NoneMark({ phrase }: { phrase: string }) {
	return (
		<span className="text-muted-foreground">
			<span aria-hidden>—</span>
			<span className="sr-only">{phrase}</span>
		</span>
	);
}

export interface ActionChipsProps {
	/** In the registry's order; a zero count shows nothing. */
	actions: readonly ActivityAction[];
	providerType: ProviderType;
	display: ActionChipDisplay;
	className?: string;
}

/** A row of chips, read aloud as a list of phrases: "3 approvals, 1 review requesting changes". */
export function ActionChips({ actions, providerType, display, className }: ActionChipsProps) {
	const shown = actions.filter((action) => action.count > 0);
	return (
		<span className={cn("flex flex-wrap items-center gap-x-3 gap-y-1", className)}>
			{shown.map((action, index) => (
				<span key={action.kind} className="inline-flex">
					<ActionChip action={action} providerType={providerType} display={display} />
					{index < shown.length - 1 && <span className="sr-only">, </span>}
				</span>
			))}
		</span>
	);
}
