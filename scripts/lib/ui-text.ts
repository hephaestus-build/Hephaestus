import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { asArray, asRecord, asString, parseJson } from "./json.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";
import { steRoot } from "./ste-words.ts";

const uiIgnorePatterns = ["**/api/**", "**/routeTree.gen.ts", "**/*.test.*", "**/mocks/**"];

/** Use oxlint's AST and the registered rule, not a second JSX parser for reports. */
export async function uiAlerts(files: string[]) {
	if (files.length === 0) {
		return [];
	}
	const root = fileURLToPath(steRoot);
	await mkdir(path.join(root, ".cache", "ste"), { recursive: true });
	const directory = await mkdtemp(path.join(root, ".cache", "ste", "ui-"));
	try {
		const config = path.join(directory, "oxlint.json");
		await writeFile(
			config,
			JSON.stringify({
				jsPlugins: [path.join(root, "webapp", "tools", "oxlint", "index.ts")],
				categories: { correctness: "off" },
				ignorePatterns: uiIgnorePatterns,
				rules: {
					"hephaestus/ui-text-voice": "error",
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
			const code = asString(diagnostic.code, "UI rule");
			if (!code.includes("ui-text-voice")) {
				throw new Error(
					`UI prose check returned ${code}. Fix the source or tool before the prose check.`,
				);
			}
			return {
				code,
				message: asString(diagnostic.message, "UI message"),
				severity: asString(diagnostic.severity, "severity"),
				filename: asString(diagnostic.filename, "UI filename"),
			};
		});
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}
