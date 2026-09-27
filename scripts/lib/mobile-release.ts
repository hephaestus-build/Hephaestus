import { asArray, asRecord, isRecord } from "./json.ts";

/**
 * The rules a mobile release is held to before any EAS job is queued. The preflight script supplies
 * the environment, the file reader and the GitHub answer; nothing here reaches the network.
 */

export type ReleaseProfile = "preview" | "production";
export type ReleaseMode = "build" | "update";

/** Every missing or malformed prerequisite, in words a maintainer can act on; empty when ready. */
export function releaseProblems({
	env,
	profile,
	mode,
	readText,
}: {
	env: Record<string, string | undefined>;
	profile: ReleaseProfile;
	mode: ReleaseMode;
	/** The file's text, or undefined when it does not exist. */
	readText: (path: string) => string | undefined;
}): string[] {
	const problems: string[] = [];
	const need = (name: string, why: string): string | undefined => {
		const value = env[name]?.trim();
		if (value === undefined || value === "") {
			problems.push(`${name} is not set: ${why}`);
			return undefined;
		}
		return value;
	};

	need("EXPO_TOKEN", "the Expo robot token EAS acts as");
	need("EAS_PROJECT_ID", "the EAS project the app belongs to");
	need("EAS_OWNER", "the Expo account or organization that owns the project");
	if (profile === "preview") {
		need("PREVIEW_INSTANCE", "the staging Hephaestus preview builds offer on their welcome screen");
	}

	// Updates bypass store review, so a production binary must only accept signed ones.
	if (profile === "production" || mode === "update") {
		const certificate = need(
			"EXPO_UPDATES_CODE_SIGNING_CERTIFICATE",
			"the path, relative to mobile/, of the public certificate binaries verify updates with",
		);
		if (
			certificate !== undefined &&
			readText(`mobile/${certificate}`)?.includes("BEGIN CERTIFICATE") !== true
		) {
			problems.push(`mobile/${certificate} is not a PEM certificate`);
		}
	}
	if (mode === "update") {
		const key = need("EXPO_UPDATES_CODE_SIGNING_KEY", "the private key updates are signed with");
		if (key !== undefined && !key.includes("PRIVATE KEY")) {
			problems.push("EXPO_UPDATES_CODE_SIGNING_KEY is not a PEM private key");
		}
	}
	return problems;
}

/**
 * Whether the commit being released passed CI, from the `workflow_runs` GitHub lists for the CI
 * workflow and that commit. The newest completed run decides; a run still going means wait. Running
 * the release from the default branch says nothing on its own, since the branch can move ahead of CI.
 */
export function ciVerdict(
	response: unknown,
	commit: string,
): { passed: true } | { passed: false; reason: string } {
	const runs = asArray(asRecord(response, "workflow runs response").workflow_runs, "workflow_runs")
		.filter(isRecord)
		.filter((run) => run.head_sha === commit)
		.toSorted((a, b) => String(b.created_at).localeCompare(String(a.created_at)));
	if (runs.some((run) => run.status !== "completed")) {
		return {
			passed: false,
			reason: `CI is still running for ${commit}; release once it has passed`,
		};
	}
	const newest = runs[0];
	if (newest === undefined) {
		return { passed: false, reason: `CI has not run for ${commit}` };
	}
	return newest.conclusion === "success"
		? { passed: true }
		: {
				passed: false,
				reason: `CI ${String(newest.conclusion)} for ${commit}; release a commit that passed`,
			};
}
