import { existsSync, readFileSync } from "node:fs";
import { parseArgs } from "node:util";

import { parseJson } from "./lib/json.ts";
import {
	ciVerdict,
	type ReleaseMode,
	type ReleaseProfile,
	releaseProblems,
} from "./lib/mobile-release.ts";
import { output, repositoryCli } from "./lib/process.ts";

/**
 * Refuses a mobile release before any EAS job is queued: when the commit did not pass CI, or when the
 * accounts and keys it needs are missing. Which binaries an update reaches is EAS's to decide, by
 * runtime version; this does not guess it. docs/contributor/mobile.mdx § Releases has the procedure.
 */

function oneOf<T extends string>(
	value: string | undefined,
	allowed: readonly T[],
	name: string,
): T {
	const match = allowed.find((candidate) => candidate === value);
	if (match === undefined) {
		throw new Error(`--${name} must be one of ${allowed.join(", ")}`);
	}
	return match;
}

const { values } = parseArgs({
	options: {
		profile: { type: "string" },
		mode: { type: "string" },
		// The commit to hold to CI; the workflows pass the one they check out.
		commit: { type: "string" },
		repository: { type: "string" },
	},
	strict: true,
});
const profile: ReleaseProfile = oneOf(values.profile, ["preview", "production"], "profile");
const mode: ReleaseMode = oneOf(values.mode, ["build", "update"], "mode");

const problems = releaseProblems({
	env: process.env,
	profile,
	mode,
	readText: (path) => (existsSync(path) ? readFileSync(path, "utf8") : undefined),
});

if (values.commit === undefined || values.repository === undefined) {
	problems.push("--commit and --repository name the commit whose CI run the release depends on");
} else {
	const verdict = ciVerdict(
		parseJson(
			await output("gh", [
				"api",
				`repos/${values.repository}/actions/workflows/cicd.yml/runs?head_sha=${values.commit}&per_page=50`,
			]),
		),
		values.commit,
	);
	if (!verdict.passed) {
		problems.push(verdict.reason);
	}
}

if (problems.length > 0) {
	throw new Error(`The ${profile} ${mode} cannot run:\n- ${problems.join("\n- ")}`);
}

// Fails on a token that does not authenticate or a project the account cannot see.
for (const args of [["whoami"], ["project:info", "--non-interactive"]]) {
	await output(process.execPath, [repositoryCli(), "-C", "mobile", "exec", "eas", ...args], {
		env: { APP_VARIANT: profile },
	});
}

console.log(`The ${profile} ${mode} of ${values.commit} has what it needs.`);
