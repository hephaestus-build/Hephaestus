import { CircleAlertIcon, type LucideIcon, WrenchIcon } from "lucide-react";

import type { StatusDef } from "@/components/common/status-def";

/**
 * Why a practice stands in "What needs your attention": the work the developer had already put in
 * went back to nothing, or a review wrote about the practice for the first time in this run.
 */
export type AttentionKind = "reset" | "new";

/**
 * The glyph, its colour and the sentence a pointer reads for each kind, shaped like
 * `OBSERVATION_OUTCOME_PRESENTATION`: status lives in the icon, never in the words beside it, and
 * the two kinds never share a glyph, since the icon is the only channel left where colour is not
 * available (WCAG 2.2 SC 1.4.1).
 */
export const ATTENTION_DEFS = {
	reset: {
		label: "Back to no clean work",
		icon: CircleAlertIcon,
		className: "text-destructive",
		description:
			"Your work had started to answer this feedback, and a review has raised the practice again.",
	},
	new: {
		label: "New feedback",
		icon: WrenchIcon,
		className: "text-warning",
		description: "A review wrote feedback about this practice on work it looked at.",
	},
} as const satisfies Record<
	AttentionKind,
	Pick<StatusDef, "label" | "description"> & { icon: LucideIcon; className: string }
>;

/** One registry entry, as a row carries it. */
export type AttentionDef = (typeof ATTENTION_DEFS)[AttentionKind];
