import { DIRECTION_CHARS, FACT_CHARS, type Facts, type ReviewOptions } from "./contract.ts";

export function oneLine(text: string, max: number): string {
	const flat = text.replaceAll(/\s+/gu, " ").trim();
	if (flat.length <= max) {
		return flat;
	}
	// Do not cut between the two halves of a surrogate pair.
	const end = /[\uD800-\uDBFF]/u.test(flat.charAt(max - 2)) ? max - 2 : max - 1;
	return `${flat.slice(0, end)}…`;
}

/** The text as it is when it is already one line within the bound, else folded to one. */
function withinLine(text: string, max: number): string {
	return text.length <= max && !/[\r\n]/u.test(text) && text.trim() !== ""
		? text
		: oneLine(text, max);
}

/**
 * The items joined with `separator`, as many as fit in `max` characters. The rest are counted, so
 * that a cut list does not read as the whole list.
 */
export function listWithin(items: readonly string[], max: number, separator = ", "): string {
	let list = "";
	for (const [index, item] of items.entries()) {
		const next = list === "" ? item : `${list}${separator}${item}`;
		const left = items.length - index - 1;
		const rest = left > 0 ? `${separator}and ${String(left)} more` : "";
		if (`${next}${rest}`.length > max) {
			const omitted = items.length - index;
			return list === ""
				? `${String(omitted)} items`
				: `${list}${separator}and ${String(omitted)} more`;
		}
		list = next;
	}
	return list;
}

const FACT_KEY = /^[A-Za-z][A-Za-z0-9]{0,63}$/u;

/**
 * A program's facts within the bounds: an empty string is no fact, and a long or multi-line string is
 * folded to one line. A key that is not a fact name is camelCased, unless that name is taken.
 */
function foldedFacts(facts: Facts): Facts {
	const folded: Facts = {};
	for (const [key, value] of Object.entries(facts)) {
		const camel = key.replaceAll(/[^A-Za-z0-9]+(?<next>.?)/gu, (_, next: string) =>
			next.toUpperCase(),
		);
		const name = FACT_KEY.test(key) || Object.hasOwn(facts, camel) ? key : camel;
		if (typeof value !== "string") {
			folded[name] = value;
		} else if (value.trim() !== "") {
			folded[name] = withinLine(value, FACT_CHARS);
		}
	}
	return folded;
}

/**
 * What a program gives its review, within the bounds that can be met without changing what it means:
 * a long or multi-line text is folded to one line, and an empty fact is no fact. Text that is already
 * within the bounds stays byte for byte. Too many items stay an error of the program.
 */
export function foldedReviewOptions(options: ReviewOptions | undefined): ReviewOptions | undefined {
	if (options === undefined) {
		return undefined;
	}
	const { directions, facts, leads } = options;
	return {
		...options,
		...(directions === undefined
			? {}
			: { directions: directions.map((direction) => withinLine(direction, DIRECTION_CHARS)) }),
		...(facts === undefined ? {} : { facts: foldedFacts(facts) }),
		...(leads === undefined
			? {}
			: {
					leads: leads.map((lead) =>
						lead.facts === undefined ? lead : { ...lead, facts: foldedFacts(lead.facts) },
					),
				}),
	};
}

/**
 * The directions for a line scan that matched nothing: what it scanned, and what a pattern of one
 * line cannot see. Without them, an empty list reads as a clean result.
 */
export function noMatchDirections(linesAdded: number, filesScanned: number): string[] {
	return [
		`Scanned ${String(linesAdded)} added lines in ${String(filesScanned)} files for this practice's line patterns; none matched.`,
		"A pattern sees one line: what spans lines or has no keyword, such as an early return, a discarded result or a missing else, is yours to enumerate from the diff.",
	];
}

// Label sampled rows so readers cannot mistake them for the whole change.
export function sampleNote(scan: { matched: number; hints: readonly unknown[] }): string[] {
	const omitted = scan.matched - scan.hints.length;
	return omitted > 0
		? [
				`${String(scan.matched)} matching occurrence(s); the first ${String(scan.hints.length)} are listed and ${String(omitted)} are not. Counts by kind cover every line; anything read off the listed rows alone covers only them.`,
			]
		: [];
}
