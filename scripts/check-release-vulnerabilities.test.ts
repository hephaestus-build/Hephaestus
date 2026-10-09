import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";
import {
	advisoryFile,
	evaluate,
	fetchGoAdvisory,
	renderSummary,
} from "./check-release-vulnerabilities.ts";

import { isRecord } from "./lib/json.ts";

const finding = {
	FixedVersion: "2",
	InstalledVersion: "1",
	PkgName: "lib",
	Severity: "HIGH",
	VulnerabilityID: "CVE-1",
};
const report = { Results: [{ Vulnerabilities: [finding] }] };
const policy = { componentInventories: [], exceptions: [], goAdvisories: [], schemaVersion: 3 };
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

await test("matches an exception only on the exact platform manifest it names, and every other field", () => {
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
	assert.deepEqual(rejectedWith({}), []);
	// A rebuilt image is a different manifest: the same package versions do not carry the review over.
	assert.deepEqual(rejectedWith({ digest: `sha256:${"b".repeat(64)}` }), ["server|CVE-1|lib|1"]);
	assert.deepEqual(
		evaluate("server", report, { ...policy, exceptions: [exception] }, now).rejected,
		["server|CVE-1|lib|1"],
		"without a subject no exception can be bound",
	);
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

const HELPER = "/usr/local/bin/helper";
const LAUNCHER = "/usr/local/bin/launcher";
const rebuilt = `sha256:${"c".repeat(64)}`;
const rebuiltSubject = {
	digest: rebuilt,
	platform: "linux/amd64",
	reference: `registry.example/server@${rebuilt}`,
};
const goFinding = {
	...finding,
	PkgName: "stdlib",
	InstalledVersion: "1.25.0",
	VulnerabilityID: "CVE-9",
};
const goResults = [
	{ Target: "usr/local/bin/helper", Type: "gobinary", Vulnerabilities: [goFinding] },
	{ Target: "usr/local/bin/launcher", Type: "gobinary" },
	{ Target: "app/server.jar", Type: "jar" },
];
/** The reviewed manifest the inventory was captured from; the rebuilt subject is another one. */
const inventory = {
	id: "server-amd64-go",
	image: "server",
	platform: "linux/amd64",
	captureDigest: digest,
	files: [
		{ path: HELPER, sha256: "1".repeat(64) },
		{ path: LAUNCHER, sha256: "2".repeat(64) },
	],
};
const ADVISORY = "GO-2026-0001";
/** Published advisory bytes as served, hashed before anything parses them. */
const advisoryText = (document: Record<string, unknown>) => Buffer.from(JSON.stringify(document));
const advisoryRaw = advisoryText({
	id: ADVISORY,
	aliases: ["CVE-9"],
	affected: [{ package: { name: "stdlib", ecosystem: "Go" } }],
});
const sha256 = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");
const absence = {
	componentInventory: inventory.id,
	digest,
	goAdvisory: ADVISORY,
	evidence: "https://github.com/example/project/issues/9",
	expires: "2026-10-01T00:00:00Z",
	image: "server",
	installedVersion: "1.25.0",
	justification: "the vulnerable function is not linked into either binary",
	justificationCategory: "vulnerable_code_not_present",
	owner: "@security",
	package: "stdlib",
	platform: "linux/amd64",
	status: "not_affected",
	vulnerability: "CVE-9",
};
const goEvidence = {
	digest: rebuilt,
	platform: "linux/amd64",
	goBinaries: new Set([HELPER, LAUNCHER]),
	sha256: new Map<string, string | null>([
		[HELPER, "1".repeat(64)],
		[LAUNCHER, "2".repeat(64)],
		// The JAR beside them changed with the rebuild; it is not Go and not part of the identity.
		["/app/server.jar", "9".repeat(64)],
	]),
};

function componentRejected(
	change: {
		results?: unknown[];
		evidence?: typeof goEvidence | undefined;
		exception?: Record<string, unknown>;
		subject?: typeof rebuiltSubject;
		advisory?: Uint8Array | undefined;
		reviewedSha256?: string;
	} = {},
): string[] {
	const subjectUsed = change.subject ?? rebuiltSubject;
	const raw = "advisory" in change ? change.advisory : advisoryRaw;
	return evaluate(
		"server",
		{ ArtifactName: subjectUsed.reference, Results: change.results ?? goResults },
		{
			...policy,
			componentInventories: [inventory],
			goAdvisories: [{ id: ADVISORY, sha256: change.reviewedSha256 ?? sha256(advisoryRaw) }],
			exceptions: [{ ...absence, ...change.exception }],
		},
		now,
		subjectUsed,
		{
			evidence: "evidence" in change ? change.evidence : goEvidence,
			advisories: raw === undefined ? new Map() : new Map([[ADVISORY, raw]]),
		},
	).rejected;
}

await test("lets a reviewed code-absence claim follow byte-identical Go binaries into a rebuilt image", () => {
	assert.deepEqual(componentRejected(), []);
});

await test("withholds component reuse unless every Go binary is the reviewed one", () => {
	const blocked = ["server|CVE-9|stdlib|1.25.0"];
	const withHash = (path: string, hash: string | null) => ({
		...goEvidence,
		sha256: new Map([...goEvidence.sha256, [path, hash]]),
	});
	const cases: [string, Parameters<typeof componentRejected>[0]][] = [
		["no Syft evidence", { evidence: undefined }],
		["evidence of another manifest", { evidence: { ...goEvidence, digest } }],
		["changed bytes under the same compiler", { evidence: withHash(HELPER, "3".repeat(64)) }],
		["a file digest Syft did not record", { evidence: withHash(LAUNCHER, null) }],
		[
			"a missing file digest",
			{
				evidence: {
					...goEvidence,
					sha256: new Map([...goEvidence.sha256].filter(([path]) => path !== LAUNCHER)),
				},
			},
		],
		[
			"a new Go binary without findings",
			{ results: [...goResults, { Target: "usr/bin/extra", Type: "gobinary" }] },
		],
		[
			"a new Go binary only Syft catalogued",
			{ evidence: { ...goEvidence, goBinaries: new Set([HELPER, LAUNCHER, "/usr/bin/extra"]) } },
		],
		[
			"a removed Go binary",
			{
				results: goResults.filter((result) => result.Target !== "usr/local/bin/launcher"),
				evidence: { ...goEvidence, goBinaries: new Set([HELPER]) },
			},
		],
		[
			"an ambiguous Go target path",
			{ results: [...goResults, { Target: "usr/local/bin/../bin/helper", Type: "gobinary" }] },
		],
		[
			"the same finding outside a Go target",
			{ results: [{ Target: "alpine 3.22", Type: "alpine", Vulnerabilities: [goFinding] }] },
		],
		[
			"another platform",
			{
				subject: { ...rebuiltSubject, platform: "linux/arm64" },
				evidence: { ...goEvidence, platform: "linux/arm64" },
				exception: { platform: "linux/arm64" },
			},
		],
	];
	for (const [name, change] of cases) {
		assert.deepEqual(componentRejected(change), blocked, name);
	}
	// A claim reviewed on one manifest cannot name an inventory captured from another.
	assert.deepEqual(componentRejected({ exception: { digest: rebuilt } }), blocked);
	// Reuse covers only the reviewed advisory: another finding in the same binary still blocks.
	assert.deepEqual(
		componentRejected({
			results: [
				{
					...goResults[0],
					Vulnerabilities: [goFinding, { ...goFinding, VulnerabilityID: "CVE-10" }],
				},
				...goResults.slice(1),
			],
		}),
		["server|CVE-10|stdlib|1.25.0"],
	);
});

await test("withholds component reuse unless the published Go advisory is the reviewed one and names the finding", () => {
	const blocked = ["server|CVE-9|stdlib|1.25.0"];
	const document = {
		id: ADVISORY,
		aliases: ["CVE-9"],
		affected: [{ package: { name: "stdlib", ecosystem: "Go" } }],
	};
	// Each opposite is hashed as reviewed, so only its identity can refuse it.
	const reviewed = (raw: Uint8Array) => ({ advisory: raw, reviewedSha256: sha256(raw) });
	const cases: [string, Parameters<typeof componentRejected>[0]][] = [
		["no advisory bytes", { advisory: undefined }],
		["bytes other than the reviewed ones", { advisory: Buffer.from(` ${advisoryRaw.toString()}`) }],
		["another advisory at the root", reviewed(advisoryText({ ...document, id: "GO-2026-0002" }))],
		[
			"an advisory that does not alias the finding",
			reviewed(advisoryText({ ...document, aliases: ["CVE-8"] })),
		],
		[
			"an advisory for another package",
			reviewed(
				advisoryText({
					...document,
					affected: [{ package: { name: "golang.org/x/net", ecosystem: "Go" } }],
				}),
			),
		],
		[
			"an advisory outside the Go ecosystem",
			reviewed(
				advisoryText({
					...document,
					affected: [{ package: { name: "stdlib", ecosystem: "npm" } }],
				}),
			),
		],
		["malformed JSON", reviewed(Buffer.from('{"id":'))],
		["bytes that are not UTF-8", reviewed(Buffer.from([0xff, 0xfe, 0x7b]))],
	];
	for (const [name, change] of cases) {
		assert.deepEqual(componentRejected(change), blocked, name);
	}
	// The finding may carry the advisory's own id rather than an alias.
	assert.deepEqual(
		componentRejected({
			results: [
				{ ...goResults[0], Vulnerabilities: [{ ...goFinding, VulnerabilityID: ADVISORY }] },
				...goResults.slice(1),
			],
			exception: { vulnerability: ADVISORY },
		}),
		[],
	);
});

await test("holds component inventories and their exceptions to one reviewed shape", () => {
	const errorsWith = (
		exceptions: Record<string, unknown>[],
		inventories: Record<string, unknown>[] = [inventory],
		advisories: Record<string, unknown>[] = [{ id: ADVISORY, sha256: "f".repeat(64) }],
	): string =>
		evaluate(
			"server",
			{ Results: [] },
			{ ...policy, componentInventories: inventories, goAdvisories: advisories, exceptions },
			now,
		).errors.join("\n");
	assert.equal(errorsWith([absence]), "");
	const { componentInventory: _inventory, ...noInventory } = absence;
	assert.match(errorsWith([noInventory]), /componentInventory and goAdvisory are named together/u);
	const { goAdvisory: _advisory, ...noAdvisory } = absence;
	assert.match(errorsWith([noAdvisory]), /componentInventory and goAdvisory are named together/u);
	const { digest: _digest, ...noDigest } = absence;
	assert.throws(() => errorsWith([noDigest]), /malformed vulnerability policy/u);
	assert.match(errorsWith([{ ...absence, digest: rebuilt }]), /was not reviewed on the manifest/u);
	assert.match(errorsWith([{ ...absence, goAdvisory: "GO-2026-0002" }]), /unreviewed Go advisory/u);
	for (const advisory of [
		{ id: "CVE-2026-0001", sha256: "f".repeat(64) },
		{ id: ADVISORY, sha256: "F".repeat(64) },
	]) {
		assert.match(errorsWith([absence], [inventory], [advisory]), /Go advisory/u);
	}
	assert.match(
		errorsWith(
			[absence],
			[inventory],
			[
				{ id: ADVISORY, sha256: "f".repeat(64) },
				{ id: ADVISORY, sha256: "e".repeat(64) },
			],
		),
		/malformed or repeated/u,
	);
	assert.match(
		errorsWith([absence], [{ ...inventory, captureDigest: "sha256:short" }]),
		/malformed capture digest/u,
	);
	for (const narrower of [
		{ justificationCategory: "vulnerable_code_not_in_execute_path" },
		{ justificationCategory: "component_not_present" },
	]) {
		assert.match(
			errorsWith([{ ...absence, ...narrower }]),
			/only for not_affected vulnerable_code_not_present/u,
		);
	}
	const { justificationCategory: _category, ...accepted } = absence;
	assert.match(
		errorsWith([{ ...accepted, status: "affected" }]),
		/only for not_affected vulnerable_code_not_present/u,
	);
	assert.match(
		errorsWith([{ ...absence, componentInventory: "other" }]),
		/unknown component inventory/u,
	);
	assert.match(errorsWith([{ ...absence, image: "webapp" }]), /another image or platform/u);
	assert.match(
		errorsWith([absence], [inventory, { ...inventory, id: "second" }]),
		/one component inventory per image/u,
	);
	for (const path of ["usr/local/bin/helper", "/a/../b", HELPER.replace("/bin", "//bin")]) {
		assert.match(
			errorsWith([absence], [{ ...inventory, files: [{ path, sha256: "1".repeat(64) }] }]),
			/malformed or repeated path/u,
			path,
		);
	}
	assert.match(
		errorsWith([absence], [{ ...inventory, files: [...inventory.files, inventory.files[0]] }]),
		/malformed or repeated path/u,
	);
	assert.match(
		errorsWith([absence], [{ ...inventory, files: [{ path: HELPER, sha256: "XYZ" }] }]),
		/malformed SHA-256/u,
	);
	assert.match(errorsWith([absence], [{ ...inventory, files: [] }]), /names no files/u);
});

const goModule = (binary: string) => ({
	name: "example.com/helper",
	version: "v1.0.0",
	type: "go-module",
	foundBy: "go-module-binary-cataloger",
	locations: [{ path: binary, annotations: { evidence: "primary" } }],
});

await test("the gate runs Syft on the exact subject only when a component-bound exception applies", async () => {
	const directory = await mkdtemp(nodePath.join(tmpdir(), "vulnerability-gate-"));
	const repository = "registry.example/server";
	const fixture = nodePath.join(directory, "native.syft.json");

	await writeFile(
		fixture,
		JSON.stringify({
			descriptor: {
				configuration: {
					catalogers: {
						used: [
							"go-module-binary-cataloger",
							"file-metadata-cataloger",
							"file-digest-cataloger",
						],
					},
					search: { scope: "squashed" },
				},
			},
			source: {
				type: "image",
				name: repository,
				metadata: {
					manifestDigest: rebuilt,
					mediaType: "application/vnd.oci.image.manifest.v1+json",
					os: "linux",
					architecture: "amd64",
					repoDigests: [`${repository}@${rebuilt}`],
				},
			},
			artifacts: [goModule(HELPER), goModule(LAUNCHER)],
			files: inventory.files.map((file) => ({
				location: { path: file.path },
				digests: [{ algorithm: "sha256", value: file.sha256 }],
			})),
		}),
	);
	const preload = nodePath.join(directory, "fetch.mjs");
	await writeFile(
		preload,
		`globalThis.fetch = async () => new Response(Buffer.from(${JSON.stringify(advisoryRaw.toString())}));`,
	);
	const bin = nodePath.join(directory, "bin");
	await mkdir(bin);
	// The vendor boundary: record the arguments and write the native document where `-o` asks.
	await writeFile(
		nodePath.join(bin, "syft"),
		[
			"#!/bin/sh",
			`printf '%s\\n' "$@" > "${directory}/syft-arguments"`,
			`for argument; do case "$argument" in syft-json=*) cp "${fixture}" "\${argument#syft-json=}" ;; esac; done`,
			"",
		].join("\n"),
		{ mode: 0o755 },
	);
	const reportPath = nodePath.join(directory, "server-linux-amd64.json");
	await writeFile(
		reportPath,
		JSON.stringify({ ArtifactName: rebuiltSubject.reference, Results: goResults }),
	);
	let runs = 0;
	const gate = async (exceptions: unknown[]) => {
		runs += 1;
		const policyPath = nodePath.join(directory, `policy-${runs}.json`);
		await writeFile(
			policyPath,
			JSON.stringify({
				...policy,
				componentInventories: [inventory],
				goAdvisories: [{ id: ADVISORY, sha256: sha256(advisoryRaw) }],
				exceptions,
			}),
		);
		const resultPath = nodePath.join(directory, `result-${runs}.json`);
		const child = spawnSync(
			process.execPath,
			[
				"--import",
				preload,
				nodePath.join(import.meta.dirname, "check-release-vulnerabilities.ts"),
				"server",
				"linux/amd64",
				rebuilt,
				repository,
				reportPath,
				policyPath,
				resultPath,
			],
			{
				encoding: "utf8",
				env: { ...process.env, GITHUB_STEP_SUMMARY: "", PATH: `${bin}:${process.env.PATH ?? ""}` },
			},
		);
		const result: unknown = JSON.parse(await readFile(resultPath, "utf8"));
		assert.ok(isRecord(result));
		assert.equal(typeof result.status, "string");
		return { child, result };
	};

	// The CLI judges expiry against the real clock.
	const expires = new Date(Date.now() + 86_400_000).toISOString().replace(/\.\d{3}Z$/u, "Z");

	// An exception bound to the reviewed manifest needs no component evidence, so Syft never runs.
	const exact = await gate([
		{ ...absence, expires, componentInventory: undefined, goAdvisory: undefined, digest: rebuilt },
	]);
	assert.equal(exact.result.status, "pass", exact.child.stderr);
	assert.equal(existsSync(nodePath.join(directory, "syft-arguments")), false);

	const component = await gate([{ ...absence, expires }]);
	assert.equal(component.result.status, "pass", component.child.stderr);
	const argumentText = await readFile(nodePath.join(directory, "syft-arguments"), "utf8");
	const argumentsSent = argumentText.split("\n");
	for (const expected of [
		["--from", "registry"],
		[`${repository}@${rebuilt}`, "--platform"],
		["linux/amd64", "--scope"],
		["squashed", "--config"],
		["--override-default-catalogers", "go-module-binary-cataloger"],
	]) {
		const at = argumentsSent.indexOf(expected[0] ?? "");
		assert.notEqual(at, -1, expected.join(" "));
		assert.equal(argumentsSent[at + 1], expected[1], expected.join(" "));
	}
	assert.ok(
		argumentsSent.some((argument) => argument.endsWith(nodePath.join("security", "syft.yaml"))),
	);
	assert.ok(
		existsSync(nodePath.join(directory, "server-linux-amd64.syft.json")),
		"the native document stays beside the report",
	);
	assert.deepEqual(
		await readFile(nodePath.join(directory, `server-linux-amd64.${ADVISORY}.json`)),
		advisoryRaw,
	);

	// A scan that fails grants nothing: the gate stops without a result.
	await writeFile(nodePath.join(bin, "syft"), "#!/bin/sh\nexit 3\n", { mode: 0o755 });
	await assert.rejects(gate([{ ...absence, expires }]), /ENOENT/u);
});

await test("reads a schema 2 policy only to verify a stored bundle, bound to each exception's digest", () => {
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
	const stored = { schemaVersion: 2, exceptions: [exception] };
	const reported = { ...report, ArtifactName: subject.reference };
	assert.throws(
		() => evaluate("server", reported, stored, now, subject),
		/malformed vulnerability policy/u,
	);
	assert.deepEqual(
		evaluate("server", reported, stored, now, subject, { historical: true }).rejected,
		[],
	);
	const rebuiltReport = { ...report, ArtifactName: rebuiltSubject.reference };
	assert.deepEqual(
		evaluate("server", rebuiltReport, stored, now, rebuiltSubject, { historical: true }).rejected,
		["server|CVE-1|lib|1"],
		"a stored pass that relied on another manifest no longer verifies",
	);
	assert.throws(
		() =>
			evaluate(
				"server",
				reported,
				{ schemaVersion: 2, exceptions: [{ ...exception, componentInventory: "x" }] },
				now,
				subject,
				{ historical: true },
			),
		/malformed vulnerability policy/u,
	);
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

await test("official advisory acquisition is bounded and retains raw bytes", async (context) => {
	const requests: string[] = [];
	const fetchMock = context.mock.method(
		globalThis,
		"fetch",
		async (url: string, options: RequestInit) => {
			requests.push(url);
			assert.equal(options.redirect, "error");
			assert.ok(options.signal instanceof AbortSignal);
			return new Response(advisoryRaw);
		},
	);
	assert.deepEqual(await fetchGoAdvisory(ADVISORY), advisoryRaw);
	assert.deepEqual(requests, [`https://vuln.go.dev/ID/${ADVISORY}.json`]);
	assert.equal(advisoryFile(ADVISORY), `advisory-${ADVISORY}.json`);
	await assert.rejects(fetchGoAdvisory("../secret"), /malformed Go advisory/u);
	assert.equal(requests.length, 1);
	fetchMock.mock.mockImplementation(async () => new Response("missing", { status: 404 }));
	await assert.rejects(fetchGoAdvisory(ADVISORY), /answered 404/u);
	fetchMock.mock.mockImplementation(async () => new Response(new Uint8Array(1024 * 1024 + 1)));
	await assert.rejects(fetchGoAdvisory(ADVISORY), /exceeds/u);
	fetchMock.mock.mockImplementation(async () => {
		throw new Error("native network failure");
	});
	await assert.rejects(fetchGoAdvisory(ADVISORY), /native network failure/u);
});
