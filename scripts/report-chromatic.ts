import { appendFileSync, readFileSync, rmSync } from "node:fs";

import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import { asArray, asRecord, asString } from "./lib/json.ts";

const REPORT_PATH = "webapp/chromatic-report.xml";

function buildUrl(value: string | undefined) {
	const url = URL.parse(value ?? "");
	return url?.protocol === "https:" &&
		url.hostname === "www.chromatic.com" &&
		url.username === "" &&
		url.password === "" &&
		url.pathname === "/build" &&
		url.searchParams.has("appId") &&
		url.searchParams.has("number")
		? url
		: undefined;
}

export function verifyTerminalReport(
	xml: string,
	expectedUrl: string | undefined,
): string | undefined {
	try {
		SyntaxValidator.validate(xml);
		const parsed: unknown = new XMLParser({
			ignoreAttributes: false,
			isArray: (name) => ["testsuite", "testcase", "property"].includes(name),
		}).parse(xml);
		const suites = asArray(
			asRecord(asRecord(parsed, "XML").testsuites, "testsuites").testsuite,
			"suites",
		);
		if (suites.length !== 1) return "Expected one returned Chromatic build report.";
		const suite = asRecord(suites[0], "suite");
		const property = (owner: Record<string, unknown>, name: string) => {
			const matches = asArray(asRecord(owner.properties, "properties").property, "property")
				.map((entry) => asRecord(entry, "property"))
				.filter((entry) => entry["@_name"] === name);
			if (matches.length !== 1) throw new Error("Missing or duplicate report property");
			return asString(asRecord(matches[0], "property")["@_value"], name);
		};
		const expected = buildUrl(expectedUrl);
		const reported = buildUrl(property(suite, "buildUrl"));
		if (
			!expected ||
			!reported ||
			expected.href !== reported.href ||
			reported.searchParams.get("number") !== property(suite, "buildNumber")
		)
			return "Report does not identify the returned Chromatic build.";
		const status = property(suite, "buildStatus");
		if (!["PASSED", "ACCEPTED"].includes(status)) {
			const label = ["IN_PROGRESS", "PENDING", "BROKEN", "FAILED", "CANCELLED", "DENIED"].includes(
				status,
			)
				? status
				: "unrecognized";
			return `Visual coverage not verified: returned build status is ${label}. Publish-only and unfinished builds are not visual approval.`;
		}
		const cases = asArray(suite.testcase, "testcases");
		if (
			cases.length === 0 ||
			count(asString(suite["@_tests"], "tests")) !== cases.length ||
			count(asString(suite["@_errors"], "errors")) !== 0 ||
			count(asString(suite["@_failures"], "failures")) !== 0
		)
			return "Report has missing test cases, inconsistent totals, or failures.";
		for (const entry of cases) {
			const testCase = asRecord(entry, "testcase");
			// Chromatic labels this property 'result' but writes the upstream test status.
			if (
				!["PASSED", "ACCEPTED"].includes(property(testCase, "result")) ||
				"failure" in testCase ||
				"error" in testCase
			)
				return "Visual coverage not verified: a reported test is unfinished or unsuccessful.";
		}
		return undefined;
	} catch {
		return "Missing or invalid structured Chromatic report evidence.";
	}
}

function count(value: string | undefined): number | undefined {
	if (!value || !/^\d+$/u.test(value)) return undefined;
	const parsed = Number(value);
	return Number.isSafeInteger(parsed) ? parsed : undefined;
}

export function visualVerdict(env: NodeJS.ProcessEnv, report?: string) {
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
	const reportError =
		report === undefined
			? "Missing structured Chromatic report evidence."
			: verifyTerminalReport(report, env.CHROMATIC_BUILD_URL);
	if (reportError) return result("unavailable", false, reportError);
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

export function coverageSummary(env: NodeJS.ProcessEnv, report?: string) {
	const verdict = visualVerdict(env, report);
	let link = "";
	const url = buildUrl(env.CHROMATIC_BUILD_URL);
	if (url)
		link = `\n[Open Chromatic build](${url.href.replaceAll("(", "%28").replaceAll(")", "%29")})\n`;

	return `## Chromatic visual coverage: ${verdict.state}\n\n${verdict.message}\n${link}\nRecovery: [contributor guide](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/contributor/ci-cd.mdx#chromatic-visual-coverage).\n`;
}

if (import.meta.main) {
	if (process.argv[2] === "--clear") {
		rmSync(REPORT_PATH, { force: true });
	} else {
		let report: string | undefined;
		try {
			report = readFileSync(REPORT_PATH, "utf8");
		} catch {
			/* Missing evidence is a failed verdict, not a script crash. */
		}
		const verdict = visualVerdict(process.env, report);
		const summary = coverageSummary(process.env, report);
		if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
		console.log(summary);
		if (!verdict.pass) console.log(`::error::${verdict.message}`);
		else if (verdict.state === "policy-skipped") console.log(`::warning::${verdict.message}`);
		process.exitCode = verdict.pass ? 0 : 1;
	}
}
