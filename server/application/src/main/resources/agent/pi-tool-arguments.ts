import { isRecord } from "./pi-observation-normalize.ts";

/** Decode only JSON containers. Invalid text and scalar values stay unchanged for validation. */
function container(value: unknown, kind: "object" | "array"): unknown {
	let decoded = value;
	if (typeof value === "string") {
		try {
			decoded = JSON.parse(value);
		} catch {
			return value;
		}
	}
	return (kind === "array" ? Array.isArray(decoded) : isRecord(decoded) && !Array.isArray(decoded))
		? decoded
		: value;
}

/** Keep line coordinates intact when they arrive as numbers copied from a numbered view. */
function line(value: unknown): unknown {
	if (typeof value !== "string") {
		return value;
	}
	const digits = /^(?:\d+|L\d+|\[L\d+\])$/iu.exec(value.trim());
	if (digits === null) {
		return value;
	}
	const coordinate = Number(value.replaceAll(/[^0-9]/gu, ""));
	return Number.isSafeInteger(coordinate) ? coordinate : value;
}

/**
 * Repair transport forms of one observation before Pi validates scalar types, never outcomes, quotes, paths, or
 * sides. The observation itself is typed: a string, a list or any other non-object becomes an empty object, which
 * the required fields refuse.
 */
export function prepareObservationArguments(args: unknown): Record<string, unknown> {
	if (!isRecord(args) || Array.isArray(args)) {
		return {};
	}
	const evidence = container(args.evidence, "object");
	if (!isRecord(evidence) || Array.isArray(evidence)) {
		return args;
	}
	const citationList = container(evidence.citations, "array");
	const citations =
		isRecord(citationList) && !Array.isArray(citationList) ? [citationList] : citationList;
	return {
		...args,
		evidence: {
			...evidence,
			citations: Array.isArray(citations)
				? citations.map((citation: unknown) => {
						if (!isRecord(citation) || Array.isArray(citation)) {
							return citation;
						}
						return Object.fromEntries(
							Object.entries(citation).map(([key, value]) => [
								key,
								key === "startLine" || key === "endLine" ? line(value) : value,
							]),
						);
					})
				: citations,
		},
	};
}
