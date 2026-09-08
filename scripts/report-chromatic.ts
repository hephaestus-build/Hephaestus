import { appendFileSync } from "node:fs";

function count(value: string | undefined): number | undefined {
	if (!value || !/^\d+$/u.test(value)) return undefined;
	const parsed = Number(value);
	return Number.isSafeInteger(parsed) ? parsed : undefined;
}

export function visualVerdict(env: NodeJS.ProcessEnv) {
	const code = count(env.CHROMATIC_CODE);
	const captured = count(env.CHROMATIC_CAPTURED);
	const inherited = count(env.CHROMATIC_INHERITED);
	const tests = count(env.CHROMATIC_TESTS);
	const errors = count(env.CHROMATIC_ERRORS);
	const changes = count(env.CHROMATIC_CHANGES);
	const interactions = count(env.CHROMATIC_INTERACTIONS);
	const result = (state: string, pass: boolean, message: string) => ({ state, pass, message });
	if (env.CHROMATIC_OUTCOME === "skipped" && env.CHROMATIC_POLICY_SKIP === "true")
		return result(
			"policy-skipped",
			true,
			"Not tested: fork or dependency-bot policy. This is not visual approval.",
		);
	if (code === 5 || code === 11 || code === 12)
		return result(
			"quota-skipped",
			false,
			"Coverage blocked by an account limit. Account owner: resolve the limit, then follow baseline recovery.",
		);
	if (code === 1 || code === 2)
		return result(
			"failed",
			false,
			"Review visual differences and fix component/interaction errors before accepting a baseline.",
		);
	if (env.CHROMATIC_OUTCOME !== "success" || code !== 0)
		return result(
			"unavailable",
			false,
			"Chromatic did not complete successfully. Check action logs, credentials and service status before retrying.",
		);
	if ((errors ?? 0) > 0 || (interactions ?? 0) > 0)
		return result(
			"failed",
			false,
			"Chromatic exited successfully but reported component/interaction errors. Fix the errors before accepting a baseline.",
		);
	if (
		captured === undefined ||
		inherited === undefined ||
		tests === undefined ||
		tests === 0 ||
		errors !== 0 ||
		changes === undefined ||
		interactions !== 0 ||
		captured + inherited === 0
	)
		return result(
			"unavailable",
			false,
			"No usable visual coverage evidence. Check account limits, project testing settings and action outputs.",
		);
	return captured > 0
		? result(
				"tested-build",
				true,
				`Returned build: ${captured} captured snapshots, ${inherited} inherited, ${changes} visual changes; passed without errors. Approved builds may be reused: these counts do not prove new captures in this CI run.`,
			)
		: result(
				"inherited",
				true,
				`Returned build: ${inherited} inherited snapshots, no new captures. Reused coverage, not a newly tested baseline.`,
			);
}

export function coverageSummary(env: NodeJS.ProcessEnv) {
	const verdict = visualVerdict(env);
	let link = "";
	const url = URL.parse(env.CHROMATIC_BUILD_URL ?? "");
	if (
		url?.protocol === "https:" &&
		url.hostname === "www.chromatic.com" &&
		url.username === "" &&
		url.password === "" &&
		url.pathname === "/build"
	)
		link = `\n[Open Chromatic build](${url.href.replaceAll("(", "%28").replaceAll(")", "%29")})\n`;

	return `## Chromatic visual coverage: ${verdict.state}\n\n${verdict.message}\n${link}\nRecovery: [contributor guide](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/contributor/ci-cd.mdx#chromatic-visual-coverage).\n`;
}

if (import.meta.main) {
	const verdict = visualVerdict(process.env);
	const summary = coverageSummary(process.env);
	if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
	console.log(summary);
	if (!verdict.pass) console.log(`::error::${verdict.message}`);
	else if (verdict.state === "policy-skipped") console.log(`::warning::${verdict.message}`);
	process.exitCode = verdict.pass ? 0 : 1;
}
