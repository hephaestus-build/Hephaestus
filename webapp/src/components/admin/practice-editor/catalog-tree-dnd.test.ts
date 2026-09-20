import { describe, expect, it } from "vitest";

import { getCatalogDropTarget } from "./catalog-tree-dnd";

interface Entry {
	slug: string;
	name: string;
	groupSlug?: string;
	displayOrder: number;
}

const entry = (slug: string, groupSlug: string | undefined, displayOrder: number): Entry => ({
	slug,
	name: slug,
	groupSlug,
	displayOrder,
});

describe("catalog tree drop target", () => {
	const entries = [
		entry("after", "delivery", 1),
		entry("source", "quality", 0),
		entry("before", "delivery", 0),
	];

	it("places before or after a row in another group", () => {
		expect(getCatalogDropTarget(entries, "source", "delivery", "after")).toStrictEqual({
			groupSlug: "delivery",
			position: 1,
		});
		expect(getCatalogDropTarget(entries, "source", "delivery", "after", true)).toStrictEqual({
			groupSlug: "delivery",
			position: 2,
		});
	});

	it("does not count the moving row when it stays in its own group", () => {
		expect(getCatalogDropTarget(entries, "before", "delivery", "after", true)).toStrictEqual({
			groupSlug: "delivery",
			position: 1,
		});
		expect(getCatalogDropTarget(entries, "before", "delivery", "after")).toStrictEqual({
			groupSlug: "delivery",
			position: 0,
		});
	});

	it("orders a displayOrder tie by name", () => {
		const tied = [entry("beta", "delivery", 0), entry("alpha", "delivery", 0)];

		expect(getCatalogDropTarget(tied, "source", "delivery", "beta")).toStrictEqual({
			groupSlug: "delivery",
			position: 1,
		});
	});

	it("appends to empty groups and Unassigned", () => {
		expect(getCatalogDropTarget(entries, "source", "empty")).toStrictEqual({
			groupSlug: "empty",
			position: 0,
		});
		expect(getCatalogDropTarget(entries, "source", null)).toStrictEqual({
			groupSlug: null,
			position: 0,
		});
	});

	it("ignores the active row as an anchor", () => {
		expect(getCatalogDropTarget(entries, "source", "quality", "source")).toBeNull();
	});

	it("refuses a drop onto an anchor the destination does not hold", () => {
		expect(
			getCatalogDropTarget(entries, "source", "delivery", "gone-in-a-later-render"),
		).toBeNull();
		expect(getCatalogDropTarget(entries, "before", "delivery", "source")).toBeNull();
	});
});
