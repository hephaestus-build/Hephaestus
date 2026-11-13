/**
 * Lints `server/openapi.yaml` with Redocly CLI and the rules in `server/redocly.yaml`. Each rule is
 * enforced; docs/contributor/ci-cd.mdx § API compatibility owns the list and its reasons.
 *
 * Usage: vp run gate:openapi-lint
 */
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import { CAPTURE_LIMIT_BYTES, exitStatus } from "./lib/process.ts";

const SPEC = fileURLToPath(new URL("../server/openapi.yaml", import.meta.url));
const CONFIG = fileURLToPath(new URL("../server/redocly.yaml", import.meta.url));

/** Redocly's report in `format`, and its progress log. Redocly sends no telemetry and checks for no update. */
export function lintSpec(
	spec: string,
	format: "stylish" | "github-actions" | "json",
): { passed: boolean; report: string; log: string } {
	const result = spawnSync(
		process.execPath,
		[
			fileURLToPath(import.meta.resolve("@redocly/cli/bin/cli.js")),
			"lint",
			spec,
			"--config",
			CONFIG,
			"--format",
			format,
		],
		{
			encoding: "utf8",
			env: { ...process.env, REDOCLY_TELEMETRY: "off", REDOCLY_SUPPRESS_UPDATE_NOTICE: "true" },
			maxBuffer: CAPTURE_LIMIT_BYTES,
		},
	);
	if (result.error !== undefined) {
		throw result.error;
	}
	if (result.status !== 0 && result.status !== 1) {
		throw new Error(
			`Redocly exited with ${exitStatus(result.status, result.signal)}:\n${result.stderr}`,
		);
	}
	return { passed: result.status === 0, report: result.stdout, log: result.stderr };
}

if (import.meta.main) {
	try {
		const { passed, report, log } = lintSpec(
			SPEC,
			process.env.GITHUB_ACTIONS === "true" ? "github-actions" : "stylish",
		);
		console.log(`${log}${report}`.trimEnd());
		if (!passed) {
			console.log(
				"::error title=OpenAPI lint::server/openapi.yaml breaks a rule of server/redocly.yaml. See docs/contributor/ci-cd.mdx § API compatibility.",
			);
			process.exitCode = 1;
		}
	} catch (error) {
		console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
