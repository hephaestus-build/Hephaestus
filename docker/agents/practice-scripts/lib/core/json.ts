import { findNodeAtLocation, getNodeValue, parseTree, type ParseError } from "jsonc-parser";
/** Narrow parsed JSON (or any foreign value) to a plain object — arrays and null are not objects here. */
export function isJsonObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** The string a field holds, or undefined for anything else. An empty string holds no value. */
export function optionalString(value: unknown): string | undefined {
	return typeof value === "string" && value !== "" ? value : undefined;
}

export function optionalNumber(value: unknown): number | undefined {
	return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** Parsed records keep their original line extents; malformed arrays never shift an entry's identity. */
export function recordArrayEntries(source: string, field: string) {
	const errors: ParseError[] = [];
	const root = parseTree(source, errors, { disallowComments: true, allowTrailingComma: false });
	if (root === undefined || errors.length > 0) {
		return null;
	}
	const array = findNodeAtLocation(root, [field]);
	if (array?.type !== "array") {
		return null;
	}
	const entries = (array.children ?? []).map((node, index) => {
		const value: unknown = getNodeValue(node);
		return {
			value,
			index,
			first: source.slice(0, node.offset).split("\n").length,
			last: source.slice(0, node.offset + node.length).split("\n").length,
		};
	});
	return entries.every((entry) => isJsonObject(entry.value)) ? entries : null;
}

export function jsonArray(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [];
}
