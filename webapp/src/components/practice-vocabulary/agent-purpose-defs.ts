import { ArrowDownWideNarrowIcon, ChartScatterIcon, ListChecksIcon, SplitIcon } from "lucide-react";
import { type ComponentType, createElement } from "react";

import type { AgentBinding } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import { statusValues } from "@/components/common/status-def";

export type AgentPurpose = AgentBinding["purpose"];

/** A kind's glyph. `size` in pixels, because Heph's mark sizes itself from it, not from a class. */
type ModelKindIcon = ComponentType<{ size: number; className?: string }>;

/** Heph's mark at rest: a kind mark marks a list row, and a row does not move. */
function HephKindIcon({ size, className }: { size: number; className?: string }) {
	return createElement(HephIcon, { size, className, animated: false });
}

interface AgentPurposeDef {
	/** The purpose's own name: its row on AI models and the toasts about it. */
	title: string;
	/** The name in a list that already says it is about models, such as a *Models* column. */
	short: string;
	/** The model as running text names it: "needs a decision model". */
	noun: string;
	withArticle: string;
	description: string;
	/**
	 * Whether only precompute scripts call it. False for review and Heph: a script can call the review
	 * model too, but that model is not a precompute one. Which APIs serve a purpose is the server's
	 * fact, sent as each model's `purposes`; a connection's API names them in `LLM_API_PROTOCOL_LABELS`.
	 */
	precompute: boolean;
	/** The glyph `ModelKindMark` draws on its neutral tile. */
	icon: ModelKindIcon;
	/**
	 * The glyph's colour, as complete class strings. A hue tells the three precompute kinds apart;
	 * each stays at least 30° (OKLCH) from success, warning, destructive and mentor, so a kind never
	 * reads as a status or the accent. Review and Heph stay neutral.
	 */
	tone: string;
}

/**
 * Every purpose a binding serves, in the order AI models shows them. A plain map rather than
 * `StatusDefs`: a purpose is a kind of model, not a state, so it has no badge to draw. It renders
 * through `ModelKindMark`, a tinted glyph on a neutral tile. Its status on AI models is
 * `PURPOSE_STATUS_DEFS`.
 */
export const AGENT_PURPOSE_DEFS = {
	PRACTICE_REVIEW: {
		title: "Practice reviews",
		short: "Review",
		noun: "review model",
		withArticle: "the review model",
		description: "Checks connected project work and conversations against your practices.",
		precompute: false,
		icon: ListChecksIcon,
		tone: "text-foreground",
	},
	MENTOR: {
		title: "Heph",
		short: "Heph",
		noun: "Heph model",
		withArticle: "the Heph model",
		description:
			"Powers conversations with Heph. Every member is offered Heph once an assignment here is ready.",
		precompute: false,
		icon: HephKindIcon,
		tone: "text-foreground",
	},
	PRACTICE_DECISION: {
		title: "Decision model",
		short: "Decision",
		noun: "decision model",
		withArticle: "a decision model",
		description:
			"Answers short questions about each place a script finds, such as whether a comment explains why.",
		precompute: true,
		icon: SplitIcon,
		tone: "text-violet-700 dark:text-violet-300",
	},
	PRACTICE_EMBEDDING: {
		title: "Embedding model",
		short: "Embedding",
		noun: "embedding model",
		withArticle: "an embedding model",
		description: "Finds code and text close in meaning to what a script looks for.",
		precompute: true,
		icon: ChartScatterIcon,
		tone: "text-cyan-700 dark:text-cyan-300",
	},
	PRACTICE_RERANKING: {
		title: "Reranking model",
		short: "Reranking",
		noun: "reranking model",
		withArticle: "a reranking model",
		description: "Puts the places a script finds in order, most relevant first.",
		precompute: true,
		icon: ArrowDownWideNarrowIcon,
		tone: "text-fuchsia-700 dark:text-fuchsia-300",
	},
} satisfies Record<AgentPurpose, AgentPurposeDef>;

/** Every purpose, in page order; also the values `?purpose=` accepts. */
export const AGENT_PURPOSES = statusValues(AGENT_PURPOSE_DEFS);

/** The purposes under *Models for precompute scripts*, in page order. */
export const PRECOMPUTE_PURPOSES = AGENT_PURPOSES.filter(isPrecomputePurpose);

export function isAgentPurpose(value: string): value is AgentPurpose {
	return Object.hasOwn(AGENT_PURPOSE_DEFS, value);
}

export function isPrecomputePurpose(purpose: AgentPurpose): boolean {
	return AGENT_PURPOSE_DEFS[purpose].precompute;
}

/** The link text that opens a missing model's purpose on AI models. */
export function assignModelLabel(purpose: AgentPurpose): string {
	return `Assign ${AGENT_PURPOSE_DEFS[purpose].withArticle}`;
}

/** The link text that opens a bound but failing model's purpose on AI models. */
export function checkModelLabel(purpose: AgentPurpose): string {
	return `Check the ${AGENT_PURPOSE_DEFS[purpose].noun}`;
}
