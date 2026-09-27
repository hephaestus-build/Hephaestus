import { readdirSync, readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

// Vitest runs on Node, which has every ES2023 array method; Hermes lacks the change-array-by-copy ones
// and throws on the first call, on a device, where no test here runs. `mobile/AGENTS.md` has the rule.
const MISSING_IN_HERMES = /\.(?<method>toSorted|toReversed|toSpliced|with)\(/gu;

function appSources(): string[] {
	return readdirSync(new URL(".", import.meta.url), { recursive: true, encoding: "utf8" }).filter(
		(file) => /\.tsx?$/u.test(file) && !file.endsWith(".test.ts") && !file.startsWith("api/"),
	);
}

describe("the app's code on Hermes", () => {
	it("calls none of the array methods Hermes does not have", () => {
		const calls = appSources().flatMap((file) =>
			[...readFileSync(new URL(file, import.meta.url), "utf8").matchAll(MISSING_IN_HERMES)].map(
				(match) => `${file}: ${match[0]}`,
			),
		);
		expect(calls).toStrictEqual([]);
	});
});
