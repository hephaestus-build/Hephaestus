import path from "node:path";
import { XMLParser } from "fast-xml-parser";

import { isRecord } from "./json.ts";

export const CHANGELOG_DIRECTORY = "server/application/src/main/resources/db/changelog";
export const NEXT_CHANGELOG_DIRECTORY = `${CHANGELOG_DIRECTORY}/next`;

/** UTC timestamps keep lexical filename order equal to migration order. */
export function migrationTime(file: string): number {
	const match =
		/^(?<year>\d{4})(?<month>\d{2})(?<day>\d{2})(?<hour>\d{2})(?<minute>\d{2})(?<second>\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.xml$/u.exec(
			path.basename(file),
		);
	if (match === null) {
		throw new Error(`Migration filename must be YYYYMMDDHHMMSS-slug.xml: ${file}`);
	}
	const {
		year = "",
		month = "",
		day = "",
		hour = "",
		minute = "",
		second = "",
	} = match.groups ?? {};
	const date = new Date(`${year}-${month}-${day}T${hour}:${minute}:${second}Z`);
	if (!Number.isFinite(date.getTime()) || timestamp(date) !== path.basename(file).slice(0, 14)) {
		throw new Error(`Migration filename has an invalid UTC timestamp: ${file}`);
	}
	return date.getTime();
}

const timestamp = (date: Date): string => date.toISOString().replaceAll(/\D/gu, "").slice(0, 14);

export function migrationFilename(
	branch: string,
	existing: readonly string[],
	now = Date.now(),
): string {
	const slug = branch
		.toLowerCase()
		.replaceAll(/[^a-z0-9]+/gu, "-")
		.replaceAll(/^-|-$/gu, "");
	if (slug === "") {
		throw new Error("Create a branch before drafting a migration");
	}
	const latest = Math.max(0, ...existing.map(migrationTime));
	return `${timestamp(new Date(Math.max(now, latest + 1000)))}-${slug}.xml`;
}

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
