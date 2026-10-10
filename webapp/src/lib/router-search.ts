import { defaultParseSearch, defaultStringifySearch } from "@tanstack/react-router";

import { hasText } from "@/lib/text";

/**
 * The router's search serialisation with lists as repeated keys, `?repo=acme/api&repo=acme/web`, the
 * way an HTML form and `URLSearchParams.getAll` read them, instead of the default's JSON array. Each
 * item keeps the default's encoding of a single value, so it reads back with its type. A key that
 * appears once reads back as one value, which `multiValue` in `search-params.ts` accepts.
 *
 * RFC 3986 § 3.4 allows `/`, `:`, `@` and `,` as data in a query, so they stay readable:
 * `repo=acme/api`, `detail=person:ada`.
 */
export function stringifySearch(search: Record<string, unknown>): string {
	const query = Object.entries(search)
		.flatMap(([key, value]) => {
			const items: readonly unknown[] =
				Array.isArray(value) && value.every(isScalar) ? value : [value];
			return items.map((item) => defaultStringifySearch({ [key]: item }).slice(1));
		})
		.filter(hasText)
		.join("&")
		.replaceAll(/%(?:2F|3A|40|2C)/gu, decodeURIComponent);
	return query === "" ? "" : `?${query}`;
}

/** Reads what `stringifySearch` writes: the items of a repeated key are decoded like single values. */
export function parseSearch(searchStr: string): Record<string, unknown> {
	const search: Record<string, unknown> = { ...defaultParseSearch(searchStr) };
	for (const [key, value] of Object.entries(search)) {
		if (Array.isArray(value)) {
			search[key] = value.map(parseItem);
		}
	}
	return search;
}

/** The app's search serialisation, for every router: the app's, a test's, and Storybook's. */
export const ROUTER_SEARCH = { parseSearch, stringifySearch };

function isScalar(value: unknown): boolean {
	return typeof value === "string" || typeof value === "number" || typeof value === "boolean";
}

function parseItem(item: unknown): unknown {
	if (typeof item !== "string") {
		return item;
	}
	try {
		const parsed: unknown = JSON.parse(item);
		return parsed;
	} catch {
		return item;
	}
}
