import { readFileSync } from "node:fs";

import { type ParseError, parse, printParseErrorCode } from "jsonc-parser";

/**
 * Reads a JSONC file — a tool config, with comments and trailing commas — and refuses a broken one:
 * `parse` alone returns whatever it managed to read before the error, so a missing comma would
 * quietly drop the rest of the object. The shape is the consumer's to check when it loads.
 */
// oxlint-disable-next-line typescript/no-unnecessary-type-parameters -- the caller names the shape; returning `unknown` would move this one assertion to every caller.
export function readJsonc<T>(file: URL): T {
	const errors: ParseError[] = [];
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- jsonc-parser's `parse` returns `any`.
	const value = parse(readFileSync(file, "utf8"), errors, { allowTrailingComma: true }) as T;
	const [first] = errors;
	if (first !== undefined) {
		throw new Error(
			`${file.pathname}: ${printParseErrorCode(first.error)} at offset ${first.offset}`,
		);
	}
	return value;
}
