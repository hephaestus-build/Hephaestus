import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";

/** The terms the story uses that a reader may not know, each with the words the product docs give it. */
export type TraceTerm = "event" | "result" | "withheld" | "coalesced";

export interface TraceTermDef {
	label: string;
	description: string;
}

/**
 * The glossary the story's tooltips read. The words come from the product vocabulary
 * (`docs/contributor/practice-feedback-language.md`) and the practice review glossary.
 */
export const TRACE_TERMS: Record<TraceTerm, TraceTermDef> = {
	event: {
		label: "Event",
		description:
			"Something that happened on the work, such as opened, new commits or merged. A review starts at an event.",
	},
	result: {
		label: "Observation",
		description: "The record of reviewing one practice against one piece of reviewed work.",
	},
	withheld: {
		label: "Withheld",
		description: DELIVERY_STATE_DEFS.WITHHELD.description,
	},
	coalesced: {
		label: "Coalesced",
		description:
			"Taken together with other events or reviews of the same code. New commits and edits close together are one review. A review runs no model when an earlier review already answered its practices on the same code.",
	},
};

/** The words in a sentence the glossary explains, once each, in the order they come. */
const TERM_PATTERN = /\b(?<term>observations?|withheld|coalesced|events?)\b/giu;

function termOf(word: string): TraceTerm {
	const lower = word.toLowerCase();
	if (lower.startsWith("observation")) {
		return "result";
	}
	if (lower.startsWith("event")) {
		return "event";
	}
	return lower === "withheld" ? "withheld" : "coalesced";
}

export type TermPart = string | { term: TraceTerm; text: string };

/** A sentence split into plain text and the glossary terms in it, each term marked the first time only. */
export function splitTerms(sentence: string): TermPart[] {
	const parts: TermPart[] = [];
	const seen = new Set<TraceTerm>();
	let last = 0;
	for (const match of sentence.matchAll(TERM_PATTERN)) {
		const term = termOf(match[0]);
		if (seen.has(term)) {
			continue;
		}
		seen.add(term);
		parts.push(sentence.slice(last, match.index), { term, text: match[0] });
		last = match.index + match[0].length;
	}
	parts.push(sentence.slice(last));
	return parts.filter((part) => typeof part !== "string" || part.length > 0);
}
