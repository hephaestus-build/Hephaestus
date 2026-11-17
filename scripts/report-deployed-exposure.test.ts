import assert from "node:assert/strict";
import { test } from "node:test";

import { parseKev } from "./lib/kev.ts";
import {
	EXPOSURE_MARKER,
	EXPOSURE_TITLE,
	exposureFingerprint,
	exposures,
	planExposureIssue,
	renderExposureBody,
	reportFindings,
} from "./report-deployed-exposure.ts";

const digest = `sha256:${"b".repeat(64)}`;
const subject = `ghcr.io/hephaestus-build/server@${digest}`;
const report = {
	SchemaVersion: 2,
	ArtifactName: subject,
	Results: [
		{
			Target: "layers/paketo-buildpacks_bellsoft-liberica/jre/release",
			Type: "jvm",
			Vulnerabilities: [
				{
					VulnerabilityID: "CVE-2026-114938",
					PkgName: "openjdk",
					InstalledVersion: "25.0.5.0.3+5-LTS",
					Severity: "MEDIUM",
				},
				{
					VulnerabilityID: "CVE-2026-2",
					PkgName: "openjdk",
					InstalledVersion: "25.0.5.0.3+5-LTS",
					Severity: "CRITICAL",
				},
			],
		},
		{
			Target: "Java",
			Type: "jar",
			Vulnerabilities: [
				{
					VulnerabilityID: "CVE-2026-3",
					PkgName: "guava",
					InstalledVersion: "32.1.2-jre",
					FixedVersion: "33.7.2",
					Severity: "HIGH",
				},
				{
					VulnerabilityID: "CVE-2026-4",
					PkgName: "guava",
					InstalledVersion: "32.1.2-jre",
					Severity: "LOW",
				},
			],
		},
		{ Target: "ubuntu", Type: "ubuntu" },
	],
};
const kev = parseKev({
	catalogVersion: "2026.11.17",
	vulnerabilities: [
		{
			cveID: "CVE-2026-114938",
			vulnerabilityName: "OpenJDK Base64 decoder",
			dateAdded: "2026-11-17",
			dueDate: "2026-12-08",
			knownRansomwareCampaignUse: "Unknown",
		},
	],
});
const context = {
	kevVersion: "2026.11.17",
	runUrl: "https://github.com/o/r/actions/runs/1",
	scannedAt: "2026-11-18T04:37:00Z",
	targets: "staging and production",
};

void test("a KEV listing, a CRITICAL finding and a policy refusal each need a response", () => {
	const listed = exposures(
		reportFindings(report),
		kev,
		new Map([[digest, new Set(["server|CVE-2026-3|guava|32.1.2-jre"])]]),
	);
	assert.deepEqual(
		listed.map((item) => [item.vulnerability, item.reasons, item.kevDueDate]),
		[
			["CVE-2026-114938", ["kev"], "2026-12-08"],
			["CVE-2026-2", ["critical"], undefined],
			["CVE-2026-3", ["refused"], undefined],
		],
	);
	// A MEDIUM finding that KEV lists is urgent although no gate blocks it.
	assert.equal(listed[0]?.severity, "MEDIUM");
});

void test("the issue opens on a finding, stays quiet while the set is unchanged and closes when it clears", () => {
	const listed = exposures(reportFindings(report), kev, new Map());
	const body = renderExposureBody(listed, context);
	assert.deepEqual(planExposureIssue([], listed, context), {
		body,
		kind: "create",
		title: EXPOSURE_TITLE,
	});
	assert.ok(body.startsWith(`<!-- ${EXPOSURE_MARKER} ${exposureFingerprint(listed)} -->`));
	assert.match(body, /CVE-2026-114938 \| openjdk/u);
	assert.deepEqual(planExposureIssue([{ number: 7, body }], listed, context), {
		issue: 7,
		kind: "unchanged",
	});
	assert.equal(planExposureIssue([{ number: 7, body }], listed.slice(1), context).kind, "update");
	assert.deepEqual(planExposureIssue([{ number: 7, body }], [], context), {
		comment:
			"No deployed image has a KEV-listed, CRITICAL or refused finding as of 2026-11-18T04:37:00Z. Closed by [this run](https://github.com/o/r/actions/runs/1).",
		issues: [7],
		kind: "close",
	});
	assert.deepEqual(planExposureIssue([{ number: 8, body: "unrelated" }], [], context), {
		kind: "none",
	});
});
