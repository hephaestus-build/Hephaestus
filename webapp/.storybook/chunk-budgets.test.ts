import { gzipSync } from "node:zlib";

import { expect, it } from "vitest";

import { checkChunkBudget } from "./chunk-budgets";

it("keeps the ordinary chunk limit for application and story code", () => {
	expect(checkChunkBudget("a".repeat(500_000), ["/src/Example.stories.tsx"])).toBeUndefined();
	expect(() => checkChunkBudget("a".repeat(500_001), ["/src/Example.stories.tsx"])).toThrow(
		"Unrecognized oversized",
	);
});

it.each([
	["/repo/node_modules/storybook/dist/preview/runtime.js", 1_350_000],
	["C:\\repo\\node_modules\\axe-core\\axe.js", 600_000],
])("budgets the exact upstream module %s", (id, limit) => {
	expect(checkChunkBudget("a".repeat(limit), [id])).toContain(`${limit}/${limit} bytes`);
	expect(() => checkChunkBudget("a".repeat(limit + 1), [id])).toThrow("exceeds its tooling budget");
});

it("does not recognize similarly named application modules as tooling", () => {
	expect(() => checkChunkBudget("a".repeat(500_001), ["/src/axe.js"])).toThrow(
		"Unrecognized oversized",
	);
});

it("enforces compressed size as well as raw size", () => {
	const code = Array.from({ length: 115_000 }, (_, index) => index.toString(36)).join("-");
	expect(() => checkChunkBudget(code, ["/repo/node_modules/axe-core/axe.js"])).toThrow(
		"gzip bytes",
	);
});

it("enforces tooling gzip budgets below the ordinary raw chunk limit", () => {
	const code = Array.from({ length: 90_000 }, (_, index) => index.toString(36)).join("-");
	expect(Buffer.byteLength(code)).toBeLessThanOrEqual(500_000);
	expect(gzipSync(code).byteLength).toBeGreaterThan(165_000);
	expect(() => checkChunkBudget(code, ["/repo/node_modules/axe-core/axe.js"])).toThrow(
		"gzip bytes",
	);
	expect(checkChunkBudget(code, ["/src/Example.stories.tsx"])).toBeUndefined();
});
