/**
 * Reading JSON that lives on disk.
 *
 * `JSON.parse` returns a value nothing has checked, and these scripts are gates: a catalog, schema or
 * manifest that changed shape must fail naming the path that no longer holds what was expected, rather
 * than a few frames later as "undefined is not a function" — or, worse, pass having compared two
 * `undefined`s.
 */
import { readFileSync } from "node:fs";
import { readFile } from "node:fs/promises";

export const parseJson = (text: string): unknown => JSON.parse(text);

export const readJsonFile = async (file: string): Promise<unknown> =>
	parseJson(await readFile(file, "utf8"));

export const readJsonFileSync = (file: string): unknown => parseJson(readFileSync(file, "utf8"));

const describe = (value: unknown): string => {
	if (value === null) {
		return "null";
	}
	if (Array.isArray(value)) {
		return "an array";
	}
	return `a ${typeof value}`;
};

export const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);

const isArray = (value: unknown): value is readonly unknown[] => Array.isArray(value);

export const asRecord = (value: unknown, label: string): Record<string, unknown> => {
	if (!isRecord(value)) {
		throw new TypeError(`${label} must be a JSON object, but is ${describe(value)}`);
	}
	return value;
};

export const asArray = (value: unknown, label: string): readonly unknown[] => {
	if (!isArray(value)) {
		throw new TypeError(`${label} must be an array, but is ${describe(value)}`);
	}
	return value;
};

export const asString = (value: unknown, label: string): string => {
	if (typeof value !== "string") {
		throw new TypeError(`${label} must be a string, but is ${describe(value)}`);
	}
	return value;
};

export const asStringArray = (value: unknown, label: string): readonly string[] =>
	asArray(value, label).map((entry, index) => asString(entry, `${label}[${index}]`));

/**
 * Walks a dotted path, naming the prefix it got to. The traversals below reach several levels into a
 * JSON Schema, where the interesting failure is which level stopped being an object.
 */
export const at = (value: unknown, path: readonly string[], label: string): unknown => {
	let current = value;
	const walked: string[] = [];
	for (const key of path) {
		current = asRecord(current, [label, ...walked].join("."))[key];
		walked.push(key);
	}
	return current;
};
