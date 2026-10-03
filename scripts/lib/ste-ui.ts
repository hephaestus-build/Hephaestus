import { spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { asArray, asRecord, asString, parseJson } from "./json.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";
import { steRoot } from "./ste-words.ts";

/** Use oxlint's AST and the registered rule, not a second JSX parser for reports. */
export async function uiAlerts(files: string[], vocabulary = false) {
	if (files.length === 0) {
		return [];
	}
	const root = fileURLToPath(steRoot);
	const directory = await mkdtemp(path.join(root, ".cache", "ste", "ui-"));
	try {
		const config = path.join(directory, "oxlint.json");
		await writeFile(
			config,
			JSON.stringify({
				jsPlugins: [path.join(root, "webapp", "tools", "oxlint", "index.ts")],
				categories: { correctness: "off" },
				ignorePatterns: ["**/api/**", "**/routeTree.gen.ts", "**/*.test.*", "**/mocks/**"],
				rules: {
					"hephaestus/ste-ui-text": [vocabulary ? "warn" : "error", { allPaths: true, vocabulary }],
				},
			}),
		);
		const cli = fileURLToPath(new URL("../bin/oxlint", import.meta.resolve("oxlint")));
		const result = spawnSync(
			process.execPath,
			[cli, "--config", config, "--format=json", ...files],
			{
				cwd: root,
				encoding: "utf8",
				maxBuffer: CAPTURE_LIMIT_BYTES,
				timeout: 120_000,
			},
		);
		if (result.error !== undefined || (result.status !== 0 && result.status !== 1)) {
			throw new Error(`UI prose check did not finish. ${result.error?.message ?? result.stderr}`);
		}
		const output = asRecord(parseJson(result.stdout), "oxlint result");
		return asArray(output.diagnostics, "UI diagnostics").map((value) => {
			const diagnostic = asRecord(value, "UI diagnostic");
			return {
				code: asString(diagnostic.code, "UI rule"),
				message: asString(diagnostic.message, "UI message"),
				severity: asString(diagnostic.severity, "severity"),
				filename: asString(diagnostic.filename, "UI filename"),
			};
		});
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}
