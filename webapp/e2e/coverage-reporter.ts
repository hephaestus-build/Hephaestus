import { appendFileSync } from "node:fs";

import type { FullConfig, FullResult, Reporter, Suite, TestCase } from "@playwright/test/reporter";

interface CoverageEntry {
	title: string;
	outcome: ReturnType<TestCase["outcome"]> | "not run";
	live: boolean;
	reason?: string;
}

const cell = (value: string) => value.replaceAll("|", "\\|").replaceAll("\n", " ");

export function coverageSummary(status: FullResult["status"], tests: CoverageEntry[]) {
	const count = (outcome: CoverageEntry["outcome"]) =>
		tests.filter((test) => test.outcome === outcome).length;
	const runOutcome =
		status === "passed" && tests.every((entry) => entry.outcome === "not run")
			? "not executed"
			: status;
	const rows = tests
		.filter((test) => test.live || test.outcome === "skipped" || test.outcome === "not run")
		.map((test) => `| ${cell(test.title)} | ${test.outcome} | ${cell(test.reason ?? "—")} |`);
	return [
		"## Browser verification coverage",
		`Run outcome: **${runOutcome}**. Expected outcomes: **${count("expected")}**; unexpected: **${count("unexpected")}**; flaky: **${count("flaky")}**; skipped: **${count("skipped")}**; not run: **${count("not run")}**.`,
		"",
		"Skipped tests are not verified. The packaged-server suite does not establish live provider health.",
		"Live provider checks require configured workspaces and explicit enablement; provider mutations additionally require `E2E_MUTATE_LIVE_INTEGRATIONS=true`.",
		"",
		"### Live-provider checks and skipped tests",
		...(rows.length
			? ["| Check | Outcome | Skip reason |", "| --- | --- | --- |", ...rows]
			: ["No live-provider checks or skipped tests were selected in this run."]),
		"",
		"[Live-provider setup and mutation opt-in](https://github.com/hephaestus-build/Hephaestus/blob/main/webapp/e2e/README.md)",
		"",
	].join("\n");
}

export default class CoverageReporter implements Reporter {
	private suite: Suite | undefined;

	onBegin(_config: FullConfig, suite: Suite) {
		this.suite = suite;
	}

	onEnd(result: FullResult) {
		const summary = coverageSummary(
			result.status,
			(this.suite?.allTests() ?? []).map((testCase) => ({
				title: testCase.titlePath().filter(Boolean).join(" › "),
				outcome: testCase.results.length === 0 ? "not run" : testCase.outcome(),
				live: testCase.location.file
					.replaceAll("\\", "/")
					.endsWith("/sync-observability.live.spec.ts"),
				reason:
					testCase.annotations
						.filter((annotation) => annotation.type === "skip")
						.map((annotation) => annotation.description ?? "Skipped without a reason")
						.join("; ") || undefined,
			})),
		);
		process.stdout.write(`\n${summary}`);
		if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
	}
}
