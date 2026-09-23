/**
 * Injected practice scripts are untyped at runtime. Invalid results throw TypeError; the runner
 * records a per-practice error instead of exposing invalid data to the review.
 */

import type { Hint, HintFlag, PracticeFindings, PracticeResult, PracticeScript } from "./types.ts";

/** Narrow parsed JSON (or any foreign value) to a plain object — arrays and null are not objects here. */
export function isJsonObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** A JSON field read as text: the string it holds, or "" for anything else. */
export function text(value: unknown): string {
	return typeof value === "string" ? value : "";
}

/**
 * All a dynamic import can prove about a practice module is that it exports a callable default.
 * The signature is unverifiable at run time; the value the call RETURNS is verified by
 * `parseFindings`, which is what actually protects the runner.
 */
export function isPracticeModule(mod: unknown): mod is { default: PracticeScript } {
	return isJsonObject(mod) && typeof mod.default === "function";
}

function isHintFlag(value: unknown): value is HintFlag {
	return typeof value === "boolean" || typeof value === "number" || typeof value === "string";
}

function isString(value: unknown): value is string {
	return typeof value === "string";
}

/**
 * A hint is rendered into summary.md field by field (`file:line`, `context.slice(...)`,
 * `Object.entries(flags)`), and that rendering happens AFTER the per-practice error boundary — so an
 * incomplete hint that slips through here takes the whole run down instead of one practice.
 */
function isHint(value: unknown): value is Hint {
	return (
		isJsonObject(value) &&
		typeof value.file === "string" &&
		typeof value.line === "number" &&
		typeof value.pattern === "string" &&
		typeof value.context === "string" &&
		typeof value.inDiff === "boolean" &&
		isJsonObject(value.flags) &&
		Object.values(value.flags).every(isHintFlag)
	);
}

/** Validate a script's return value against the PracticeFindings contract. */
export function parseFindings(value: unknown, source: string): PracticeFindings {
	if (!isJsonObject(value)) {
		throw new TypeError(`${source} must return an object`);
	}

	const { hints, metrics, directions } = value;
	if (!Array.isArray(hints) || !hints.every(isHint)) {
		throw new TypeError(
			`${source}: hints must be an array of {file, line, pattern, context, inDiff, flags}`,
		);
	}
	if (!Array.isArray(directions) || !directions.every(isString)) {
		throw new TypeError(`${source}: directions must be an array of strings`);
	}
	if (!isJsonObject(metrics)) {
		throw new TypeError(`${source}: metrics must be an object`);
	}

	const numericMetrics: Record<string, number> = {};
	for (const [key, metric] of Object.entries(metrics)) {
		if (typeof metric !== "number" || !Number.isFinite(metric)) {
			// JSON.stringify writes NaN/Infinity as `null`, so an unchecked metric silently becomes a
			// hole in {slug}.json. Naming the key here makes the script's bug findable.
			throw new TypeError(`${source}: metrics.${key} must be a finite number`);
		}
		numericMetrics[key] = metric;
	}

	return { hints, metrics: numericMetrics, directions };
}

/** Validate an already-written `{slug}.json` against the full PracticeResult contract. */
export function parsePracticeResult(value: unknown, source: string): PracticeResult {
	if (!isJsonObject(value)) {
		throw new TypeError(`${source} must be an object`);
	}

	const { practice, status } = value;
	if (typeof practice !== "string" || practice.length === 0) {
		throw new TypeError(`${source}: practice must be a non-empty string`);
	}
	if (status !== "ok" && status !== "error" && status !== "timeout") {
		throw new TypeError(`${source}: status must be one of ok, error, timeout`);
	}

	return { practice, status, ...parseFindings(value, source) };
}
