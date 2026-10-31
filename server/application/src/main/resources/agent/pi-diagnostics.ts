/**
 * The review runtime's diagnostics on stderr. Each line names one event of `pi-diagnostics.json`, with
 * fields of that vocabulary only: counts, flags and closed codes. A text that a tool, a
 * model, a provider or reviewed code wrote has no field to go in, and neither has a label, a slug, a
 * path or an id: such text can hold reviewed code, a credential or a person's words, and the worker
 * copies these lines into its own log. A practice and a turn are named by their positions.
 *
 * The worker keeps only lines that this vocabulary allows (`SandboxExits.java`), so a line that
 * breaks it is dropped there too.
 */

import vocabulary from "./pi-diagnostics.json" with { type: "json" };

export type DiagnosticValue = string | number | boolean | undefined;

type FieldType = "count" | "ratio" | "flag" | readonly string[];

const EVENTS: ReadonlySet<string> = new Set(vocabulary.events);
const SCALARS = ["count", "ratio", "flag"] as const;
const FIELDS: ReadonlyMap<string, FieldType> = new Map(
	Object.entries(vocabulary.fields).map(([key, type]): [string, FieldType] => {
		const scalar = SCALARS.find((candidate) => candidate === type);
		if (scalar !== undefined) {
			return [key, scalar];
		}
		if (Array.isArray(type)) {
			return [key, type];
		}
		throw new TypeError(`pi-diagnostics.json: field ${key} has no type`);
	}),
);
const ERRORS: ReadonlySet<string> = new Set(vocabulary.fields.error);

/** The text each scalar type allows. `DiagnosticVocabulary.java` holds the same three patterns. */
export const SCALAR_TEXT = {
	count: /^\d{1,16}$/u,
	ratio: /^\d{1,6}(?:\.\d{1,6})?$/u,
	flag: /^(?:true|false)$/u,
} as const;

function textAllowed(type: FieldType, text: string): boolean {
	return typeof type === "string" ? SCALAR_TEXT[type].test(text) : type.includes(text);
}

function valueOf(type: FieldType, value: DiagnosticValue): string | undefined {
	if (type === "count") {
		return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
			? String(value)
			: undefined;
	}
	if (type === "ratio") {
		return typeof value === "number" && Number.isFinite(value) && value >= 0 && value < 1e5
			? String(Math.round(value * 1000) / 1000)
			: undefined;
	}
	if (type === "flag") {
		return typeof value === "boolean" ? String(value) : undefined;
	}
	return typeof value === "string" && textAllowed(type, value) ? value : undefined;
}

/**
 * Whether the worker keeps `line`: one event of the vocabulary, then fields of the vocabulary, each
 * once, each with a value its type allows. `SandboxExits.keeps` decides the same way.
 */
export function isDiagnosticLine(line: string): boolean {
	const rest = /^\[pi-(?:runner|provider)\] (?<rest>\S.*)$/u.exec(line)?.groups?.rest;
	if (rest === undefined) {
		return false;
	}
	const [event, ...fields] = rest.split(" ");
	if (event === undefined || !EVENTS.has(event)) {
		return false;
	}
	const seen = new Set<string>();
	return fields.every((field) => {
		const separator = field.indexOf("=");
		const key = field.slice(0, separator);
		const type = FIELDS.get(key);
		if (separator < 1 || type === undefined || seen.has(key)) {
			return false;
		}
		seen.add(key);
		return textAllowed(type, field.slice(separator + 1));
	});
}

/**
 * The line for `event`, with each field that has a value. A field or a value that the vocabulary does
 * not allow is left out and counted in `invalid`, so a mistake shows without the value it carried.
 */
export function formatDiagnostic(
	event: string,
	fields: Record<string, DiagnosticValue> = {},
	source: "runner" | "provider" = "runner",
): string {
	const parts = [`[pi-${source}] ${EVENTS.has(event) ? event : "diagnostic.invalid"}`];
	let invalid = 0;
	for (const [key, value] of Object.entries(fields)) {
		if (value === undefined) {
			continue;
		}
		const type = FIELDS.get(key);
		const text = type === undefined ? undefined : valueOf(type, value);
		if (text === undefined) {
			invalid += 1;
		} else {
			parts.push(`${key}=${text}`);
		}
	}
	if (invalid > 0) {
		parts.push(`invalid=${invalid}`);
	}
	return parts.join(" ");
}

export function diagnose(
	event: string,
	fields: Record<string, DiagnosticValue> = {},
	source: "runner" | "provider" = "runner",
): void {
	console.error(formatDiagnostic(event, fields, source));
}

/**
 * An error's class, and its closed reason code where it has one, from the vocabulary's `error` codes.
 * Never its message: a message can quote the input that failed.
 */
export function errorKind(error: unknown): string {
	if (!(error instanceof Error)) {
		return "not_an_error";
	}
	const reason: unknown = "reason" in error ? error.reason : undefined;
	const withReason = typeof reason === "string" ? `${error.name}.${reason}` : error.name;
	if (ERRORS.has(withReason)) {
		return withReason;
	}
	return ERRORS.has(error.name) ? error.name : "other";
}

/** Milliseconds as whole numbers, so a duration is a count and never a formatted text. */
export function ms(value: number): number {
	return Math.max(0, Math.round(value));
}
