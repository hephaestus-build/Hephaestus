import type { OxlintConfig } from "oxlint";

import { readJsonc } from "../jsonc.ts";

/** The file on disk lists paths in `extends`; `OxlintConfig` is the object form Vite+ takes. */
type OxlintrcFile = Omit<OxlintConfig, "extends"> & { extends?: string[] };

/**
 * The object path accepts only objects in `extends`, so each entry becomes the config it names,
 * read relative to the file that lists it — minus that config's `jsPlugins`, which the object path
 * rejects when they are relative and which every tree lists for itself anyway.
 */
export function loadLintConfig(file: URL): OxlintConfig {
	const { extends: paths, ...config } = readJsonc<OxlintrcFile>(file);
	if (paths === undefined) {
		return config;
	}
	const bases = paths.map((entry) => {
		const { jsPlugins: _plugins, ...base } = loadLintConfig(new URL(entry, file));
		return base;
	});
	return { ...config, extends: bases };
}
