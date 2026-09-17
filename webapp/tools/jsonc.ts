import { readFileSync } from "node:fs";

import { type ParseError, parse, printParseErrorCode } from "jsonc-parser";

import { isRecord } from "../src/lib/is-record.ts";

/**
 * Reads a JSONC file — a tool config, with comments and trailing commas — and refuses a broken one:
 * `parse` alone returns whatever it managed to read before the error, so a missing comma would
 * quietly drop the rest of the object. A config is an object; what its keys hold is the consumer's
 * to check when it loads.
 */
export function readJsonc(file: URL): Record<string, unknown> {
	const errors: ParseError[] = [];
	const value: unknown = parse(readFileSync(file, "utf8"), errors, { allowTrailingComma: true });
	const [first] = errors;
	if (first !== undefined) {
		throw new Error(
			`${file.pathname}: ${printParseErrorCode(first.error)} at offset ${first.offset}`,
		);
	}
	if (!isRecord(value)) {
		throw new TypeError(`${file.pathname}: a config is a JSON object`);
	}
	return value;
}
