import { readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { asRecord, asString, asStringArray, parseJson } from "./json.ts";

export const steRoot = pathToFileURL(`${path.resolve(import.meta.dirname, "../..")}${path.sep}`);
const list = asRecord(
	parseJson(readFileSync(new URL(".vale/words.json", steRoot), "utf8")),
	"STE words",
);
export const approvedWords = asStringArray(list.approved, "approved words");
export const contractions = Object.entries(asRecord(list.contractions, "contractions")).map(
	([from, to]) => ({ from, to: asString(to, from) }),
);
export const substitutions = Object.entries(asRecord(list.substitutions, "substitutions")).map(
	([from, to]) => ({ from, to: asString(to, from) }),
);

/** The first column owns each term. The other columns describe it, not new names. */
export function technicalNames(markdown: string): string[] {
	return [...markdown.matchAll(/^\|\s*\*\*(?<term>[^*]+)\*\*\s*\|/gmu)].map((match) =>
		asString(match.groups?.term, "technical name").toLowerCase(),
	);
}

export const productTerms = technicalNames(
	readFileSync(new URL("docs/contributor/practice-feedback-language.md", steRoot), "utf8"),
);
if (productTerms.length === 0) {
	throw new Error(
		"The product vocabulary has no technical terms. Restore its bold first-column terms.",
	);
}
const escape = (text: string) => text.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
const terms = new RegExp(
	`\\b(?:${productTerms
		.toSorted((a, b) => b.length - a.length)
		.map(escape)
		.join("|")})\\b`,
	"giu",
);

export function withoutTechnicalNames(text: string): string {
	return text.replaceAll(terms, (term) => " ".repeat(term.length));
}

const replacements = [...substitutions, ...contractions].map(({ from, to }) => ({
	pattern: new RegExp(`\\b${escape(from).replaceAll(" ", String.raw`\s+`)}\\b`, "giu"),
	from,
	to,
}));

export function wordAlerts(text: string): { from: string; to: string; index: number }[] {
	const prose = withoutTechnicalNames(text);
	return replacements
		.flatMap(({ pattern, from, to }) =>
			[...prose.matchAll(pattern)].map((match) => ({ from, to, index: match.index })),
		)
		.toSorted((a, b) => a.index - b.index);
}
