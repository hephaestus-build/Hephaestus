import type { OxlintConfig } from "oxlint";

import { readJsonc } from "../jsonc.ts";

const isStringArray = (value: unknown): value is string[] =>
	Array.isArray(value) && value.every((entry: unknown) => typeof entry === "string");

/**
 * The object path accepts only objects in `extends`, so each entry becomes the config it names,
 * read relative to the file that lists it — minus that config's `jsPlugins`, which the object path
 * rejects when they are relative and which every tree lists for itself anyway. The file on disk
 * lists paths in `extends`, the one key read here; oxlint checks the rest when it loads.
 */
export function loadLintConfig(file: URL): OxlintConfig {
	const { extends: paths, ...config } = readJsonc(file);
	if (paths === undefined) {
		return config;
	}
	if (!isStringArray(paths)) {
		throw new TypeError(`${file.pathname}: \`extends\` lists config paths`);
	}
	const bases = paths.map((entry) => {
		const { jsPlugins: _plugins, ...base } = loadLintConfig(new URL(entry, file));
		return base;
	});
	return { ...config, extends: bases };
}
