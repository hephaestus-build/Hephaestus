// Kept apart from changelog.ts: the push gate imports that file where no dependency is installed.
import { XMLParser } from "fast-xml-parser";

import { isRecord } from "./json.ts";

/** A parsed changelog element: attributes as `@_name`, children as lists, text as `#text`. */
export type ChangelogElement = Record<string, unknown>;

// Every element is a list, so a change set with one change and one with many read the same way.
export const changelogParser = new XMLParser({
	ignoreAttributes: false,
	isArray: (_name, _path, _leaf, isAttribute) => !isAttribute,
});

/** The child elements with this name. An element with only text, such as a plain `<sql>`, parses to its text. */
export const elements = (parent: ChangelogElement, name: string): ChangelogElement[] => {
	const value = parent[name];
	return Array.isArray(value)
		? value.flatMap((entry) => {
				if (typeof entry === "string") {
					return [{ "#text": entry }];
				}
				return isRecord(entry) ? [entry] : [];
			})
		: [];
};

export const attribute = (element: ChangelogElement, name: string): string => {
	const value = element[`@_${name}`];
	return typeof value === "string" ? value : "";
};

export function text(element: ChangelogElement): string {
	const value = element["#text"];
	if (Array.isArray(value)) {
		return value.filter((part) => typeof part === "string").join("\n");
	}
	return typeof value === "string" ? value : "";
}
