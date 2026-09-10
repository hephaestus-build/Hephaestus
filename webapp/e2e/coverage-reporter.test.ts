import { expect, it } from "vitest";

import { coverageSummary } from "./coverage-reporter";

it("separates skipped live checks from successful ordinary checks", () => {
	const summary = coverageSummary("passed", [
		{ title: "Sign in", outcome: "expected", live: false },
		{
			title: "GitHub event stream",
			outcome: "skipped",
			live: true,
			reason: "Live workspace not configured",
		},
		{ title: "GitLab sync", outcome: "skipped", live: true, reason: "Mutation opt-in required" },
	]);
	expect(summary).toContain(
		"Expected outcomes: **1**; unexpected: **0**; flaky: **0**; skipped: **2**",
	);
	expect(summary).toContain("| GitHub event stream | skipped | Live workspace not configured |");
	expect(summary).toContain("| GitLab sync | skipped | Mutation opt-in required |");
});

it("reports actual live outcomes and unexpected ordinary skips without hiding failures", () => {
	const summary = coverageSummary("failed", [
		{ title: "GitHub event stream", outcome: "unexpected", live: true },
		{ title: "GitLab sync", outcome: "flaky", live: true },
		{
			title: "Unexpected | skip",
			outcome: "skipped",
			live: false,
			reason: "First line\nsecond line",
		},
	]);
	expect(summary).toContain("Run outcome: **failed**");
	expect(summary).toContain("unexpected: **1**; flaky: **1**; skipped: **1**");
	expect(summary).toContain("| Unexpected \\| skip | skipped | First line second line |");
});

it("does not imply live coverage when a filtered run selected none", () => {
	expect(coverageSummary("passed", [])).toContain(
		"No live-provider checks or skipped tests were selected",
	);
});

it("does not call discovery or an interrupted unstarted test a skipped execution", () => {
	const summary = coverageSummary("passed", [
		{ title: "Discovered only", outcome: "not run", live: false },
	]);
	expect(summary).toContain("Run outcome: **not executed**");
	expect(summary).toContain("skipped: **0**; not run: **1**");
	expect(
		coverageSummary("failed", [{ title: "Not started", outcome: "not run", live: false }]),
	).toContain("Run outcome: **failed**");
});
