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
/** Words that the product voice never uses, beside the exclamation mark. A support reply may say "please". */
export const apologies = ["sorry", "oops"];
export const pleading = "please";
/** An exclamation mark. One before a digit is a reference, such as the GitLab merge request `!88`. */
export const exclamation = String.raw`!(?!\d)`;
/**
 * The same mark for Vale. Go's regexp has no lookahead, so this pattern consumes the character after
 * the mark. Its own Vale rule keeps that character from hiding a word from the other rules.
 */
export const exclamationForVale = String.raw`!(?:\D|$)`;

/** The words and marks of `apologies`, `pleading` and the exclamation mark, in order, in UI text. */
export function voiceAlerts(text: string): string[] {
	const words = [...apologies, pleading].join("|");
	return [...text.matchAll(new RegExp(`\\b(?:${words})\\b|${exclamation}`, "giu"))].map(
		([found]) => found,
	);
}

/** The one family that the user-facing voice spells out: "can't" is misread as "can". */
export const negativeContractions = contractions.filter(({ from }) => /n['’]t$/iu.test(from));

/** The first column owns each term. The other columns describe it, not new names. */
export function technicalNames(markdown: string): string[] {
	return [...markdown.matchAll(/^\|\s*\*\*(?<term>[^*]+)\*\*\s*\|/gmu)].map((match) =>
		asString(match.groups?.term, "technical name").toLowerCase(),
	);
}

export const productTerms = technicalNames(
	readFileSync(new URL("docs/contributor/practice-feedback-language.mdx", steRoot), "utf8"),
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

function patterns(entries: { from: string; to: string }[]) {
	return entries.map(({ from, to }) => ({
		pattern: new RegExp(`\\b${escape(from).replaceAll(" ", String.raw`\s+`)}\\b`, "giu"),
		from,
		to,
	}));
}
const replacements = {
	strict: patterns([...substitutions, ...contractions]),
	voice: patterns([...substitutions, ...negativeContractions]),
};

/** `strict` spells out every contraction. `voice` allows the positive ones ("you're", "we'll"). */
export function wordAlerts(
	text: string,
	profile: keyof typeof replacements = "strict",
): { from: string; to: string; index: number }[] {
	const prose = withoutTechnicalNames(text);
	return replacements[profile]
		.flatMap(({ pattern, from, to }) =>
			[...prose.matchAll(pattern)].map((match) => ({ from, to, index: match.index })),
		)
		.toSorted((a, b) => a.index - b.index);
}
