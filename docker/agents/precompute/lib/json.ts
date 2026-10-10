/** Reads of parsed JSON, or of any foreign value, that a script and the readers in `lib/` share. */

/** Narrow parsed JSON (or any foreign value) to a plain object — arrays and null are not objects here. */
export function isJsonObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** A JSON field read as text: the string it holds, or "" for anything else. */
export function text(value: unknown): string {
	return typeof value === "string" ? value : "";
}

/** The string a field holds, or undefined for anything else. An empty string holds no value. */
export function optionalString(value: unknown): string | undefined {
	return typeof value === "string" && value !== "" ? value : undefined;
}

/** The finite number a field holds, or undefined for anything else. */
export function optionalNumber(value: unknown): number | undefined {
	return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** The boolean a field holds, or undefined for anything else. */
export function optionalBoolean(value: unknown): boolean | undefined {
	return typeof value === "boolean" ? value : undefined;
}
