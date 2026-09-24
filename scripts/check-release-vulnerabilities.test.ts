import assert from "node:assert/strict";
import { test } from "node:test";
import { evaluate, renderSummary } from "./check-release-vulnerabilities.ts";

const finding = {
	FixedVersion: "2",
	InstalledVersion: "1",
	PkgName: "lib",
	Severity: "HIGH",
	VulnerabilityID: "CVE-1",
};
const report = { Results: [{ Vulnerabilities: [finding] }] };
const policy = { exceptions: [], schemaVersion: 2 };
const digest = `sha256:${"a".repeat(64)}`;
const subject = { digest, platform: "linux/amd64", reference: `registry.example/server@${digest}` };
const now = new Date("2026-09-01T00:00:00Z");

await test("rejects a high vulnerability without a disposition", () => {
	assert.deepEqual(evaluate("server", report, policy).rejected, ["server|CVE-1|lib|1"]);
});

await test("reports but does not reject a high vulnerability with no upstream fix", () => {
	for (const unfixable of [
		{ ...finding, FixedVersion: "" },
		{ ...finding, FixedVersion: "   " },
		Object.fromEntries(Object.entries(finding).filter(([field]) => field !== "FixedVersion")),
	]) {
		const result = evaluate("server", { Results: [{ Vulnerabilities: [unfixable] }] }, policy);
		assert.deepEqual(result.rejected, []);
		assert.deepEqual(result.highCritical, ["server|CVE-1|lib|1"]);
		assert.deepEqual(result.errors, []);
	}
	const mixed = evaluate(
		"server",
		{
			Results: [
				{
					Vulnerabilities: [
						finding,
						{ ...finding, PkgName: "other", VulnerabilityID: "CVE-2", FixedVersion: "" },
						{ ...finding, PkgName: "third", VulnerabilityID: "CVE-3", Severity: "CRITICAL" },
					],
				},
			],
		},
		policy,
	);
	assert.deepEqual(mixed.rejected, ["server|CVE-1|lib|1", "server|CVE-3|third|1"]);
	assert.deepEqual(mixed.highCritical, [
		"server|CVE-1|lib|1",
		"server|CVE-2|other|1",
		"server|CVE-3|third|1",
	]);
});

await test("rejects a report whose fixed version is not a string", () => {
	assert.throws(
		() =>
			evaluate(
				"server",
				{ Results: [{ Vulnerabilities: [{ ...finding, FixedVersion: 2 }] }] },
				policy,
			),
		/FixedVersion must be a string/u,
	);
});

await test("accepts an owned, justified, unexpired exception", () => {
	const exceptions = [
		{
			digest,
			evidence: "https://github.com/example/project/issues/1",
			expires: "2026-10-01T00:00:00Z",
			image: "server",
			installedVersion: "1",
			justification: "not reachable in the deployed configuration",
			justificationCategory: "vulnerable_code_not_in_execute_path",
			owner: "@security",
			package: "lib",
			platform: "linux/amd64",
			status: "not_affected",
			vulnerability: "CVE-1",
		},
	];
	assert.deepEqual(
		evaluate(
			"server",
			{ ...report, ArtifactName: subject.reference },
			{ ...policy, exceptions },
			now,
			subject,
		).rejected,
		[],
	);
	assert.deepEqual(
		evaluate(
			"server",
			{ ...report, ArtifactName: subject.reference },
			{ ...policy, exceptions: [{ ...exceptions[0], installedVersion: "0" }] },
			now,
			subject,
		).rejected,
		["server|CVE-1|lib|1"],
	);
});

await test("matches an exception without its digest, but still on every other field", () => {
	const exception = {
		digest,
		evidence: "https://github.com/example/project/issues/1",
		expires: "2026-10-01T00:00:00Z",
		image: "server",
		installedVersion: "1",
		justification: "not reachable in the deployed configuration",
		justificationCategory: "vulnerable_code_not_in_execute_path",
		owner: "@security",
		package: "lib",
		platform: "linux/amd64",
		status: "not_affected",
		vulnerability: "CVE-1",
	};
	const rejectedWith = (override: Partial<typeof exception>): string[] =>
		evaluate(
			"server",
			{ ...report, ArtifactName: subject.reference },
			{ ...policy, exceptions: [{ ...exception, ...override }] },
			now,
			subject,
		).rejected;
	assert.deepEqual(rejectedWith({ digest: `sha256:${"b".repeat(64)}` }), []);
	for (const override of [
		{ image: "other" },
		{ platform: "linux/arm64" },
		{ package: "other" },
		{ installedVersion: "0" },
		{ vulnerability: "CVE-2" },
	] satisfies Partial<typeof exception>[]) {
		assert.deepEqual(rejectedWith(override), ["server|CVE-1|lib|1"], JSON.stringify(override));
	}
	assert.match(
		evaluate(
			"server",
			{ ...report, ArtifactName: subject.reference },
			{
				...policy,
				exceptions: [exception, { ...exception, digest: `sha256:${"b".repeat(64)}` }],
			},
			now,
			subject,
		).errors.join("\n"),
		/duplicate exception: server\|linux\/amd64\|CVE-1\|lib\|1/u,
	);
});

await test("rejects expired and malformed exceptions", () => {
	const exceptions = [
		{
			digest,
			evidence: "https://github.com/example/project/issues/1",
			expires: "2020-01-01T00:00:00Z",
			image: "server",
			installedVersion: "1",
			justification: "reviewed",
			owner: "@security",
			package: "lib",
			platform: "linux/amd64",
			status: "affected",
			vulnerability: "CVE-1",
		},
	];
	assert.match(
		evaluate("server", report, { ...policy, exceptions }, new Date("2021-01-01")).errors[0] ?? "",
		/expired/u,
	);
	assert.throws(() => evaluate("server", {}, policy), /malformed Trivy report/u);
	assert.throws(
		() => evaluate("server", { Results: [{ Vulnerabilities: [{ Severity: "HIGH" }] }] }, policy),
		/InstalledVersion must be a string/u,
	);
	assert.match(
		evaluate("server", report, {
			...policy,
			exceptions: [exceptions[0], exceptions[0]],
		}).errors.join("\n"),
		/duplicate exception/u,
	);
	assert.match(
		evaluate(
			"server",
			report,
			{
				...policy,
				exceptions: [{ ...exceptions[0], expires: "2027-01-01T00:00:00Z" }],
			},
			now,
		).errors.join("\n"),
		/90-day limit/u,
	);
	assert.match(
		evaluate(
			"server",
			{ ...report, ArtifactName: "registry.example/server@sha256:wrong" },
			policy,
			new Date(),
			subject,
		).errors.join("\n"),
		/Trivy report is for/u,
	);
});

await test("holds a not_affected exception to one of CISA's five justifications", () => {
	const exception = {
		digest,
		evidence: "https://github.com/example/project/issues/1",
		expires: "2026-10-01T00:00:00Z",
		image: "server",
		installedVersion: "1",
		justification: "the vulnerable parser is never handed untrusted input",
		justificationCategory: "vulnerable_code_cannot_be_controlled_by_adversary",
		owner: "@security",
		package: "lib",
		platform: "linux/amd64",
		status: "not_affected",
		vulnerability: "CVE-1",
	};
	const errorsWith = (override: Partial<typeof exception>): string =>
		evaluate(
			"server",
			report,
			{ ...policy, exceptions: [{ ...exception, ...override }] },
			now,
		).errors.join("\n");

	for (const category of [
		"component_not_present",
		"vulnerable_code_not_present",
		"vulnerable_code_not_in_execute_path",
		"vulnerable_code_cannot_be_controlled_by_adversary",
		"inline_mitigations_already_exist",
	]) {
		assert.equal(errorsWith({ justificationCategory: category }), "", category);
	}

	assert.throws(
		() => errorsWith({ justificationCategory: "not_reachable" }),
		/malformed vulnerability policy/u,
	);
	const { justificationCategory: _omitted, ...uncategorised } = exception;
	assert.match(
		evaluate("server", report, { ...policy, exceptions: [uncategorised] }, now).errors.join("\n"),
		/not_affected exception must name a justification category: CVE-1/u,
	);
	assert.match(
		errorsWith({ status: "affected" }),
		/affected exception must not name a justification category: CVE-1/u,
	);
	assert.equal(
		evaluate(
			"server",
			report,
			{ ...policy, exceptions: [{ ...uncategorised, status: "affected" }] },
			now,
		).errors.join("\n"),
		"",
	);
});

await test("names the rejected findings in the run summary", () => {
	const rendered = renderSummary(
		{ digest, image: "server", platform: "linux/amd64" },
		evaluate(
			"server",
			{
				Results: [
					{
						Vulnerabilities: [finding, { ...finding, VulnerabilityID: "CVE-2", FixedVersion: "" }],
					},
				],
			},
			policy,
		),
	);
	assert.match(rendered, /server \(linux\/amd64\): fail/u);
	assert.match(rendered, /2 HIGH\/CRITICAL, 1 rejected/u);
	assert.match(rendered, /\| CVE-1 \| lib \| 1 \|/u);
	assert.doesNotMatch(rendered, /\| CVE-2 \|/u);

	const clean = renderSummary(
		{ digest, image: "server", platform: "linux/amd64" },
		evaluate("server", { Results: [] }, policy),
	);
	assert.match(clean, /server \(linux\/amd64\): pass/u);
	assert.doesNotMatch(clean, /\| Vulnerability \|/u);
});

await test("rejects malformed severities rather than treating them as below HIGH", () => {
	for (const severity of ["high", "CRITCAL", " ", 4, null]) {
		assert.throws(
			() =>
				evaluate(
					"server",
					{ Results: [{ Vulnerabilities: [{ ...finding, Severity: severity }] }] },
					policy,
				),
			/Severity/u,
		);
	}
	for (const severity of ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]) {
		const result = evaluate(
			"server",
			{ Results: [{ Vulnerabilities: [{ ...finding, Severity: severity }] }] },
			policy,
		);
		assert.equal(result.rejected.length, ["HIGH", "CRITICAL"].includes(severity) ? 1 : 0);
	}
});

await test("rejects an exception expiry that JavaScript rolls into another month", () => {
	const exception = {
		digest,
		evidence: "https://github.com/example/project/issues/1",
		expires: "2026-02-30T00:00:00Z",
		image: "server",
		installedVersion: "1",
		justification: "risk accepted pending an upgrade",
		owner: "@security",
		package: "lib",
		platform: "linux/amd64",
		status: "affected",
		vulnerability: "CVE-1",
	};
	assert.match(
		evaluate(
			"server",
			report,
			{ ...policy, exceptions: [exception] },
			new Date("2026-02-01T00:00:00Z"),
		).errors.join("\n"),
		/invalid or expired exception/u,
	);
});
