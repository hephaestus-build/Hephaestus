import { ArrowDownWideNarrowIcon, ChartScatterIcon, ListChecksIcon, SplitIcon } from "lucide-react";
import { type ComponentType, createElement } from "react";

import type { AgentBinding } from "@/api/types.gen";
import { HephIcon } from "@/components/brand/HephIcon";
import { statusValues } from "@/components/common/status-def";

export type AgentPurpose = AgentBinding["purpose"];

/** A kind's glyph. `size` in pixels, because Hephaestus's mark sizes itself from it, not from a class. */
type ModelKindIcon = ComponentType<{ size: number; className?: string }>;

function HephKindIcon({ size, className }: { size: number; className?: string }) {
	return createElement(HephIcon, { size, className, animated: false });
}

interface AgentPurposeDef {
	title: string;
	/** The name in a list that already says it is about models, such as a *Models* column. */
	short: string;
	noun: string;
	withArticle: string;
	description: string;
	/**
	 * Whether only practice scripts call it. False for review and Hephaestus: a script can call the review
	 * model too, but that model is not a model for practice scripts. Which APIs serve a purpose is the server's
	 * fact, sent as each model's `purposes`; a connection's API names them in `LLM_API_PROTOCOL_LABELS`.
	 */
	practiceScript: boolean;
	icon: ModelKindIcon;
	/**
	 * The glyph's colour, as complete class strings. A hue tells the three model kinds for practice scripts apart;
	 * each stays at least 30° (OKLCH) from success, warning, destructive and mentor, so a kind never
	 * reads as a status or the accent. Review and Hephaestus stay neutral.
	 */
	tone: string;
}

// Agent purposes are model kinds, so their registry has no status tone.
export const AGENT_PURPOSE_DEFS = {
	PRACTICE_REVIEW: {
		title: "Practice reviews",
		short: "Review",
		noun: "review model",
		withArticle: "the review model",
		description: "Checks connected project work and conversations against your practices.",
		practiceScript: false,
		icon: ListChecksIcon,
		tone: "text-foreground",
	},
	MENTOR: {
		title: "Conversations",
		short: "Conversation",
		noun: "conversation model",
		withArticle: "the conversation model",
		description:
			"Answers members in their conversations. Every member can ask once a conversation model is ready.",
		practiceScript: false,
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
		practiceScript: true,
		icon: SplitIcon,
		tone: "text-violet-700 dark:text-violet-300",
	},
	PRACTICE_EMBEDDING: {
		title: "Embedding model",
		short: "Embedding",
		noun: "embedding model",
		withArticle: "an embedding model",
		description: "Finds code and text close in meaning to what a script looks for.",
		practiceScript: true,
		icon: ChartScatterIcon,
		tone: "text-cyan-700 dark:text-cyan-300",
	},
	PRACTICE_RERANKING: {
		title: "Reranking model",
		short: "Reranking",
		noun: "reranking model",
		withArticle: "a reranking model",
		description: "Puts the places a script finds in order, most relevant first.",
		practiceScript: true,
		icon: ArrowDownWideNarrowIcon,
		tone: "text-fuchsia-700 dark:text-fuchsia-300",
	},
} satisfies Record<AgentPurpose, AgentPurposeDef>;

export const AGENT_PURPOSES = statusValues(AGENT_PURPOSE_DEFS);

export const PRACTICE_SCRIPT_PURPOSES = AGENT_PURPOSES.filter(isPracticeScriptPurpose);

export function isAgentPurpose(value: string): value is AgentPurpose {
	return Object.hasOwn(AGENT_PURPOSE_DEFS, value);
}

export function isPracticeScriptPurpose(purpose: AgentPurpose): boolean {
	return AGENT_PURPOSE_DEFS[purpose].practiceScript;
}

export function assignModelLabel(purpose: AgentPurpose): string {
	return `Assign ${AGENT_PURPOSE_DEFS[purpose].withArticle}`;
}
