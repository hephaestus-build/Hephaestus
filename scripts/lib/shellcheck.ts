/**
 * The pinned ShellCheck: one version for `gate:shell` and for the run scripts of the workflow check,
 * installed from the upstream release and verified at each use.
 */
import { execFileSync } from "node:child_process";

import { ciToolPin } from "./ci-tool-catalog.ts";
import { installPinnedBinary, toolCache } from "./pinned-binary.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";

/** The path of the pinned ShellCheck for this platform, installed into the repository cache. */
export async function prepareShellCheck(): Promise<string> {
	const pin = await ciToolPin("shellcheck");
	const binary = await installPinnedBinary({
		cache: toolCache("shellcheck"),
		version: pin.version,
		asset: { name: pin.name, sha256: pin.sha256 },
		url: pin.url.slice(0, -pin.name.length),
		executable: process.platform === "win32" ? "shellcheck.exe" : "shellcheck",
		member: pin.member,
	});
	const reported = execFileSync(binary, ["--version"], {
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	if (!reported.includes(`version: ${pin.version}`)) {
		throw new Error(`Unexpected ShellCheck version: ${reported.trim()}. Use the pinned archive.`);
	}
	return binary;
}

/**
 * The lines of `script` (1-based) that turn errexit or pipefail off. ShellCheck has no rule for them,
 * and a script that turns errexit off reports success for a command that failed. Record a status
 * explicitly instead, as in `docker build … || status=$?`.
 */
export function errexitDisabled(script: string): number[] {
	return script
		.split("\n")
		.flatMap((line, index) =>
			/(?:^|[;&|({]|\b(?:then|do|else)\b)\s*set\s+(?:(?:[-+]o\s+[\w-]+|[-+][a-zA-Z]+)\s+)*(?:\+[a-zA-Z]*e[a-zA-Z]*|\+o\s+(?:errexit|pipefail))(?![\w-])/u.test(
				executableText(line),
			)
				? [index + 1]
				: [],
		);
}

/** A line without its quoted text and its comment, so that neither can look like a command. */
function executableText(line: string): string {
	return line.replaceAll(/'[^']*'|"(?:[^"\\]|\\.)*"/gu, "''").replace(/(?:^|\s)#.*$/u, "");
}
