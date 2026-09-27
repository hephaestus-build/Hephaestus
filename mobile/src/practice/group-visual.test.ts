import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { COLORS, groupVisual, ICONS } from "./group-visual";

function read(path: string): string {
	return readFileSync(new URL(path, import.meta.url), "utf8");
}

/** Every `icon` and `color` value in the server's default catalog, wherever it sits in the tree. */
function catalogKeys(): { icons: string[]; colors: string[] } {
	const icons: string[] = [];
	const colors: string[] = [];
	const pending: unknown[] = [
		JSON.parse(
			read("../../../server/application/src/main/resources/practices/default-catalog.json"),
		),
	];
	for (let value = pending.pop(); value !== undefined; value = pending.pop()) {
		if (typeof value !== "object" || value === null) {
			continue;
		}
		for (const [key, child] of Object.entries(value)) {
			if (key === "icon" && typeof child === "string") {
				icons.push(child);
			} else if (key === "color" && typeof child === "string") {
				colors.push(child);
			} else {
				pending.push(child);
			}
		}
	}
	return { icons, colors };
}

describe("groupVisual", () => {
	it("maps every icon and colour the default catalog ships", () => {
		const { icons, colors } = catalogKeys();
		expect(icons.length).toBeGreaterThan(0);
		expect(icons.filter((icon) => !(icon in ICONS))).toStrictEqual([]);
		expect(colors.filter((color) => !(color in COLORS))).toStrictEqual([]);
	});

	it("maps every colour the web lets a workspace choose", () => {
		const web = read("../../../webapp/src/components/practice-vocabulary/group-visuals.ts");
		const match = /export const PILL[^{]*\{(?<body>[^}]*)\}/u.exec(web);
		assert.ok(match);
		assert.ok(match.groups);
		const palette = match.groups.body;
		assert.ok(palette !== undefined);
		const keys = [...palette.matchAll(/^\s*(?<key>[a-z]+):/gmu)].map((entry) => {
			assert.ok(entry.groups);
			const { key } = entry.groups;
			assert.ok(key !== undefined);
			return key;
		});
		expect(keys.length).toBeGreaterThan(10);
		expect(keys.filter((key) => !(key in COLORS))).toStrictEqual([]);
	});

	it("falls back to a grey folder for keys this build does not know, as the web does", () => {
		expect(groupVisual("SomethingNew", "chartreuse")).toStrictEqual(groupVisual("Folder", "slate"));
		expect(groupVisual()).toStrictEqual(groupVisual("Folder", "slate"));
	});
});
