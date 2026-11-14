import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import type { Acceptance } from "./bundle-budget";
import { acceptanceFile, acceptancesIn, recordAcceptances } from "./compare";

const directories: string[] = [];

afterEach(() => {
	for (const directory of directories.splice(0)) {
		rmSync(directory, { recursive: true, force: true });
	}
});

function ledger(files: Record<string, readonly Acceptance[]> = {}): string {
	const root = mkdtempSync(path.join(tmpdir(), "accepted-growth-"));
	directories.push(root);
	const directory = path.join(root, "accepted-growth");
	mkdirSync(directory);
	for (const [name, accepted] of Object.entries(files)) {
		writeFileSync(path.join(directory, name), JSON.stringify({ accepted }));
	}
	return directory;
}

const pr = (number: number) => `https://github.com/hephaestus-build/Hephaestus/pull/${number}`;
const accept = (load: string, gzipBytes: number, by: string): Acceptance => ({
	load,
	gzipBytes,
	acceptedBy: by,
	reason: "measured growth",
});

describe("acceptanceFile", () => {
	it("names the file after the pull request, or after the branch before it has one", () => {
		expect(acceptanceFile(pr(4330))).toBe("pr-4330.json");
		expect(acceptanceFile("branch ci3/Bundle_Growth")).toBe("ci3-bundle-growth.json");
		expect(() => acceptanceFile("branch ///")).toThrow(/Cannot name/u);
	});
});

describe("acceptancesIn", () => {
	it("reads every change's file as one set, and a base without the directory has none", () => {
		const one = accept("entry", 400_000, pr(1));
		const two = accept("src/a.tsx", 9000, pr(2));
		expect(acceptancesIn(ledger({ "pr-2.json": [two], "pr-1.json": [one] }))).toStrictEqual([
			one,
			two,
		]);
		expect(acceptancesIn(path.join(tmpdir(), "no-such-accepted-growth"))).toStrictEqual([]);
	});

	it("refuses a file that is not an accepted-growth file, naming it", () => {
		const directory = ledger();
		writeFileSync(path.join(directory, "pr-3.json"), JSON.stringify({ accepted: [{ load: "" }] }));
		expect(() => acceptancesIn(directory)).toThrow(/pr-3\.json is not an accepted-growth file/u);
	});
});

describe("recordAcceptances", () => {
	it("writes the change's own file and removes only files whose growth the base holds", () => {
		const spent = accept("entry", 400_000, pr(1));
		const parallel = accept("src/b.tsx", 7000, pr(2));
		const directory = ledger({ "pr-1.json": [spent], "pr-2.json": [parallel] });
		const own = [accept("src/a.tsx", 9000, pr(3))];

		const { removed } = recordAcceptances(directory, pr(3), own, [spent]);

		expect(removed).toStrictEqual(["pr-1.json"]);
		expect(readdirSync(directory).sort()).toStrictEqual(["pr-2.json", "pr-3.json"]);
		expect(JSON.parse(readFileSync(path.join(directory, "pr-3.json"), "utf8"))).toStrictEqual({
			accepted: own,
		});
	});

	it("leaves no file for a change that accepts nothing", () => {
		const directory = ledger({ "pr-3.json": [accept("src/a.tsx", 9000, pr(3))] });
		recordAcceptances(directory, pr(3), [], []);
		expect(readdirSync(directory)).toStrictEqual([]);
	});

	it("never lets two independent changes write the same file", () => {
		const spent = accept("entry", 400_000, pr(1));
		const base = { "pr-1.json": [spent] };
		const first = ledger(base);
		const second = ledger(base);

		const a = recordAcceptances(first, pr(4), [accept("src/a.tsx", 9000, pr(4))], [spent]);
		const b = recordAcceptances(second, pr(5), [accept("src/b.tsx", 7000, pr(5))], [spent]);

		// Each adds only its own file, and both remove the same base file: Git merges both changes.
		expect(path.basename(a.file)).not.toBe(path.basename(b.file));
		expect(a.removed).toStrictEqual(b.removed);
		expect(readdirSync(first)).toStrictEqual(["pr-4.json"]);
		expect(readdirSync(second)).toStrictEqual(["pr-5.json"]);
	});
});
