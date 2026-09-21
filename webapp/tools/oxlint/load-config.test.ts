import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { loadLintConfig } from "./load-config.ts";

let dir: string;

beforeEach(() => {
	dir = mkdtempSync(path.join(tmpdir(), "load-config-"));
});

afterEach(() => {
	rmSync(dir, { recursive: true, force: true });
});

function write(name: string, content: string): URL {
	const file = path.join(dir, name);
	mkdirSync(path.dirname(file), { recursive: true });
	writeFileSync(file, content);
	return pathToFileURL(file);
}

describe("loadLintConfig", () => {
	it("replaces each extends path with the config it names, read relative to the file", () => {
		write("base.jsonc", '{ "jsPlugins": ["./plugin.ts"], "rules": { "no-var": "error" } }');
		const tree = write(
			"tree/.oxlintrc.json",
			'{ "extends": ["../base.jsonc"], "jsPlugins": ["./own.ts"], "rules": { "curly": "error" } }',
		);
		expect(loadLintConfig(tree)).toStrictEqual({
			extends: [{ rules: { "no-var": "error" } }],
			jsPlugins: ["./own.ts"],
			rules: { curly: "error" },
		});
	});

	it("follows a base that extends another base", () => {
		write("root.jsonc", '{ "options": { "typeAware": true } }');
		write("layer.jsonc", '{ "extends": ["./root.jsonc"], "plugins": ["react"] }');
		const tree = write("tree.jsonc", '{ "extends": ["./layer.jsonc"] }');
		expect(loadLintConfig(tree)).toStrictEqual({
			extends: [{ extends: [{ options: { typeAware: true } }], plugins: ["react"] }],
		});
	});

	it("refuses a config that does not parse instead of returning what it could read", () => {
		const broken = write("broken.jsonc", '{ "rules": { "no-var": "error" "curly": "error" } }');
		expect(() => loadLintConfig(broken)).toThrow(/broken\.jsonc: .* at offset \d+/u);
	});
});
