import { describe, expect, it } from "vitest";

import { DATA_COLLECTION, stripRequestUserAndBreadcrumbs } from "./index";

function leaves(value: unknown): unknown[] {
	return typeof value === "object" && value !== null && !Array.isArray(value)
		? Object.values(value).flatMap(leaves)
		: [value];
}

describe("the data an error report may carry", () => {
	it("turns no category on", () => {
		expect(leaves(DATA_COLLECTION).filter((leaf) => leaf === true)).toStrictEqual([]);
		expect(DATA_COLLECTION.httpBodies).toStrictEqual([]);
	});
});

it("strips request, user, and breadcrumb fields", () => {
	const event = stripRequestUserAndBreadcrumbs({
		type: undefined,
		request: { url: "https://example.test/private?token=secret", headers: { cookie: "secret" } },
		user: { id: "person" },
		breadcrumbs: [{ category: "navigation", data: { from: "/private" } }],
	});

	expect(event.request).toBeUndefined();
	expect(event.user).toBeUndefined();
	expect(event.breadcrumbs).toBeUndefined();
});
