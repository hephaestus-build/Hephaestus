import { describe, expect, it, vi } from "vitest";

import { levelPathAt } from "./level-path";

const stack = [
	{ kind: "group", id: "code-review" },
	{ kind: "practice", id: "describe-what-and-why" },
];
const onClose = vi.fn();
const pathAt = levelPathAt(stack, {
	pageLabel: "Practice setup",
	labelOf: (entry, index) => `${entry.kind}#${index}`,
	onClose,
});

describe("levelPathAt", () => {
	it("puts the page first and names only the levels below the one asked for", () => {
		expect(pathAt(1).behind).toStrictEqual([
			{ label: "Practice setup", depth: 0 },
			{ label: "group#0", depth: 1 },
		]);
	});

	it("gives the bottom level the page alone", () => {
		expect(pathAt(0).behind).toStrictEqual([{ label: "Practice setup", depth: 0 }]);
	});

	it("hands every level the same close, so a crumb closes down to its depth", () => {
		pathAt(2).onClose(1);
		expect(onClose).toHaveBeenCalledWith(1);
	});
});
