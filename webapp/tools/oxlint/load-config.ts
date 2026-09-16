import type { OxlintConfig } from "oxlint";

import { readJsonc } from "../jsonc.ts";

/**
 * Reads a tree's `.oxlintrc.json` for Vite+, which hands oxlint the config as an object rather
 * than a path. The object path accepts only objects in `extends`, so each entry becomes the config
 * it names, read relative to the file that lists it — minus that config's `jsPlugins`, which the
 * object path rejects when they are relative and which every tree lists for itself anyway.
 */
export function loadLintConfig(file: URL): OxlintConfig {
	const config = readJsonc<OxlintConfig>(file);
	if (config.extends === undefined) {
		return config;
	}
	const bases = config.extends.map((entry) => {
		if (typeof entry !== "string") {
			return entry;
		}
		const { jsPlugins: _plugins, ...base } = loadLintConfig(new URL(entry, file));
		return base;
	});
	return { ...config, extends: bases };
}
