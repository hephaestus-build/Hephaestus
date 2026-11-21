import assert from "node:assert/strict";
import { test } from "node:test";

import { readJsonFileSync } from "./lib/json.ts";
import {
	assertRuntimeInventory,
	isPinnedBuild,
	JVM_RESULT_TYPE,
	jvmPackages,
	jvmResult,
	databaseSearchArguments,
	mergedMatches,
	rangeMatches,
	satisfiesConstraint,
	withJvmResults,
} from "./lib/jvm-runtime.ts";

// Syft 1.57.1's record of the server image's JRE, from `server@sha256:dfdd7274…` on 2026-11-17.
const jvmArtifact = {
	name: "openjdk",
	version: "25.0.5.0.3+5-LTS",
	type: "binary",
	foundBy: "java-jvm-cataloger",
	locations: [{ path: "/layers/paketo-buildpacks_bellsoft-liberica/jre/release" }],
	cpes: [{ cpe: "cpe:2.3:a:oracle:openjdk:25.0.5.0.3:*:*:*:*:*:*:*", source: "syft-generated" }],
	purl: "pkg:generic/oracle/openjdk@25.0.5.0.3%2B5-LTS",
};
const jarArtifact = {
	name: "guava",
	version: "32.1.2-jre",
	type: "java-archive",
	foundBy: "java-archive-cataloger",
	locations: [{ path: "/workspace/lib/guava.jar" }],
	cpes: [],
};
const sbom = (artifacts: readonly unknown[]) => ({ artifacts });

void test("the JVM that Syft found is read with its CPE and location", () => {
	assert.deepEqual(jvmPackages(sbom([jarArtifact, jvmArtifact])), [
		{
			cpes: ["cpe:2.3:a:oracle:openjdk:25.0.5.0.3:*:*:*:*:*:*:*"],
			location: "/layers/paketo-buildpacks_bellsoft-liberica/jre/release",
			name: "openjdk",
			version: "25.0.5.0.3+5-LTS",
		},
	]);
});

void test("a runtime version matches the pin that writes `_` for `+`", () => {
	assert.equal(isPinnedBuild("25.0.5.0.3+5-LTS", "25.0.5.0.3_5"), true);
	assert.equal(isPinnedBuild("25.0.5.0.3+5", "25.0.5.0.3_5"), true);
	assert.equal(isPinnedBuild("25.0.5.1+1-LTS", "25.0.5.0.3_5"), false);
	assert.equal(isPinnedBuild("25.0.5.0.3+50-LTS", "25.0.5.0.3_5"), false);
});

void test("the server image fails its scan when the JRE leaves the inventory", () => {
	const packages = jvmPackages(sbom([jvmArtifact]));
	assert.doesNotThrow(() => assertRuntimeInventory("server", packages));
	assert.doesNotThrow(() => assertRuntimeInventory("server", packages, "25.0.5.0.3_5"));
	assert.throws(
		() => assertRuntimeInventory("server", jvmPackages(sbom([jarArtifact]))),
		/server: Syft found no JVM/u,
	);
	assert.throws(
		() => assertRuntimeInventory("server", packages, "25.0.5.1_1"),
		/runs JVM 25\.0\.5\.0\.3\+5-LTS, not the pinned JRE 25\.0\.5\.1_1/u,
	);
	assert.throws(
		() => assertRuntimeInventory("server", jvmPackages(sbom([{ ...jvmArtifact, cpes: [] }]))),
		/without a CPE/u,
	);
	// An image that is not a Java service may have no JVM.
	assert.doesNotThrow(() => assertRuntimeInventory("postgres", []));
});

void test("the server pin is the one file that names the image's JRE", () => {
	const pin = readJsonFileSync(".github/ci-tools/buildpack-liberica-jre.json");
	assert.ok(typeof pin === "object" && pin !== null && "version" in pin);
	assert.match(String(pin.version), /^\d+\.\d+\.\d+/u);
});

const grypeMatch = (id: string, severity: string, fix: unknown) => ({
	vulnerability: { id, severity, fix },
	artifact: { name: "openjdk", version: "25.0.5.0.3+5-LTS" },
});

void test("Grype's JVM matches become one Trivy result", () => {
	const [jvm] = jvmPackages(sbom([jvmArtifact]));
	assert.ok(jvm);
	const result = jvmResult(
		{
			matches: [
				grypeMatch("CVE-2026-2", "High", { state: "fixed", versions: ["25.0.5.1"] }),
				grypeMatch("CVE-2026-1", "Critical", { state: "not-fixed", versions: [] }),
				grypeMatch("CVE-2026-3", "Negligible", { state: "wont-fix", versions: [] }),
			],
		},
		jvm,
	);
	assert.deepEqual(result, {
		Target: "layers/paketo-buildpacks_bellsoft-liberica/jre/release",
		Class: "lang-pkgs",
		Type: JVM_RESULT_TYPE,
		Vulnerabilities: [
			{
				VulnerabilityID: "CVE-2026-1",
				PkgName: "openjdk",
				InstalledVersion: "25.0.5.0.3+5-LTS",
				Severity: "CRITICAL",
			},
			{
				VulnerabilityID: "CVE-2026-2",
				PkgName: "openjdk",
				InstalledVersion: "25.0.5.0.3+5-LTS",
				FixedVersion: "25.0.5.1",
				Severity: "HIGH",
			},
			{
				VulnerabilityID: "CVE-2026-3",
				PkgName: "openjdk",
				InstalledVersion: "25.0.5.0.3+5-LTS",
				Severity: "LOW",
			},
		],
	});
	assert.throws(
		() => jvmResult({ matches: [grypeMatch("CVE-2026-4", "Severe", { state: "unknown" })] }, jvm),
		/unknown severity/u,
	);

	const report = withJvmResults({ SchemaVersion: 2, Results: [] }, [result]);
	assert.deepEqual(report.Results, [result]);
	assert.throws(() => withJvmResults(report, [result]), /already carries JVM results/u);
});

void test("a report without JVM results stays byte-identical", () => {
	const report = { SchemaVersion: 2, ArtifactName: "x" };
	assert.equal(withJvmResults(report, []), report);
});

// Rows in the shape of Grype v0.121.3's `grype db search --pkg cpe:2.3:a:oracle:openjdk -o json`. The
// CVE-2026-114938 ranges are three of the four in its database of 2026-11-21. The GraalVM package shows that
// only the JVM's own CPE product counts.
const searchRow = (id: string, severity: string, ranges: readonly unknown[]) => ({
	vulnerability: { id, severity },
	packages: [
		{ cpe: "cpe:2.3:a:oracle:openjdk:*:*:*:*:*:*:*:*", detail: { ranges } },
		{
			cpe: "cpe:2.3:a:oracle:graalvm:*:*:*:*:*:*:*:*",
			detail: { ranges: [{ version: { constraint: ">= 1" } }] },
		},
	],
});
const java4Shell = searchRow("CVE-2026-114938", "high", [
	{
		version: { type: "jvm", constraint: ">= 12, < 17.0.21.1" },
		fix: { version: "17.0.21.1", state: "fixed" },
	},
	{
		version: { type: "jvm", constraint: ">= 22, < 25.0.5.1" },
		fix: { version: "25.0.5.1", state: "fixed" },
	},
	{
		version: { type: "jvm", constraint: ">= 26, < 27.0.1.1" },
		fix: { version: "27.0.1.1", state: "fixed" },
	},
]);

void test("a build of a fourth-part fix matches the range that Grype reads on three parts", () => {
	const [jvm] = jvmPackages(sbom([jvmArtifact]));
	assert.ok(jvm);
	// The second range of CVE-2026-114938, `< 25.0.5.1`, contains 25.0.5.0.3 only by its fourth part.
	const unfixed = searchRow("CVE-2026-9", "unknown", [
		{ version: { type: "jvm", constraint: "= 8-update72 || >= 25, <= 25.0.5.0.3" } },
	]);
	const matches = rangeMatches([java4Shell, unfixed], jvm);
	assert.deepEqual(matches, [
		{
			vulnerability: {
				id: "CVE-2026-114938",
				severity: "High",
				fix: { state: "fixed", versions: ["25.0.5.1"] },
			},
			artifact: { name: "openjdk", version: "25.0.5.0.3+5-LTS" },
		},
		{
			vulnerability: { id: "CVE-2026-9", severity: "Unknown", fix: { state: "unknown" } },
			artifact: { name: "openjdk", version: "25.0.5.0.3+5-LTS" },
		},
	]);
	assert.deepEqual(jvmResult({ matches }, jvm).Vulnerabilities, [
		{
			VulnerabilityID: "CVE-2026-114938",
			PkgName: "openjdk",
			InstalledVersion: "25.0.5.0.3+5-LTS",
			FixedVersion: "25.0.5.1",
			Severity: "HIGH",
		},
		{
			VulnerabilityID: "CVE-2026-9",
			PkgName: "openjdk",
			InstalledVersion: "25.0.5.0.3+5-LTS",
			Severity: "UNKNOWN",
		},
	]);
	const fixed = { ...jvm, version: "25.0.5.1+5-LTS" };
	assert.deepEqual(rangeMatches([java4Shell], fixed), []);
	assert.equal(rangeMatches([java4Shell], { ...jvm, version: "25.0.4+7-LTS" }).length, 1);
	// Only the JVM's own CPE product counts: the GraalVM range above would match every version.
	assert.deepEqual(
		rangeMatches([java4Shell], {
			...jvm,
			cpes: ["cpe:2.3:a:eclipse:temurin:25.0.5:*:*:*:*:*:*:*"],
		}),
		[],
	);
});

void test("a constraint compares every part of the version", () => {
	const parts = [25, 0, 5, 0, 3];
	assert.equal(satisfiesConstraint(parts, "< 25.0.5.1"), true);
	assert.equal(satisfiesConstraint(parts, "< 25.0.5"), false);
	assert.equal(satisfiesConstraint(parts, "= 25.0.5.0.3"), true);
	assert.equal(satisfiesConstraint(parts, "= 25.0.5"), false);
	assert.equal(satisfiesConstraint(parts, ">= 26, < 27.0.1.1 || >= 22, <= 25.0.5.0.3"), true);
	assert.equal(satisfiesConstraint([1, 8, 0, 512], "< 1.8.0_513"), true);
	assert.throws(() => satisfiesConstraint(parts, "~> 25"), /not a comparison/u);
});

void test("a range match never replaces Grype's own match of the same vulnerability", () => {
	const [jvm] = jvmPackages(sbom([jvmArtifact]));
	assert.ok(jvm);
	const grype = [
		grypeMatch("CVE-2026-114938", "Critical", { state: "fixed", versions: ["25.0.5.1"] }),
	];
	const ranges = rangeMatches(
		[java4Shell, searchRow("CVE-2026-8", "medium", [{ version: { constraint: ">= 25" } }])],
		jvm,
	);
	assert.deepEqual(jvmResult({ matches: mergedMatches(grype, ranges) }, jvm).Vulnerabilities, [
		{
			VulnerabilityID: "CVE-2026-114938",
			PkgName: "openjdk",
			InstalledVersion: "25.0.5.0.3+5-LTS",
			FixedVersion: "25.0.5.1",
			Severity: "CRITICAL",
		},
		{
			VulnerabilityID: "CVE-2026-8",
			PkgName: "openjdk",
			InstalledVersion: "25.0.5.0.3+5-LTS",
			Severity: "MEDIUM",
		},
	]);
});

const scored = (id: string, version: string, baseScore: number) => ({
	...searchRow(id, "unknown", [{ version: { constraint: ">= 25" } }]),
	vulnerability: {
		id,
		severity: "unknown",
		severities: [{ scheme: "CVSS", value: { version, metrics: { baseScore } } }],
	},
});

void test("a range match takes the highest severity of its CVSS scores", () => {
	const [jvm] = jvmPackages(sbom([jvmArtifact]));
	assert.ok(jvm);
	// `grype db search` labels a base score of 10.0 `unknown`.
	const matches = rangeMatches(
		[
			scored("CVE-2026-10", "3.1", 10),
			scored("CVE-2026-11", "2.0", 10),
			scored("CVE-2026-12", "3.1", 5.3),
		],
		jvm,
	);
	assert.deepEqual(
		jvmResult({ matches }, jvm).Vulnerabilities,
		[
			["CVE-2026-10", "CRITICAL"],
			["CVE-2026-11", "HIGH"],
			["CVE-2026-12", "MEDIUM"],
		].map(([id, severity]) => ({
			VulnerabilityID: id,
			PkgName: "openjdk",
			InstalledVersion: "25.0.5.0.3+5-LTS",
			Severity: severity,
		})),
	);
});

void test("a range for Windows only does not match, and the search names no result limit", () => {
	const [jvm] = jvmPackages(sbom([jvmArtifact]));
	assert.ok(jvm);
	const windows = searchRow("CVE-2026-13", "high", []);
	windows.packages[0] = {
		cpe: "cpe:2.3:a:oracle:openjdk:*:*:*:*:*:*:*:*",
		detail: {
			ranges: [{ version: { constraint: ">= 25" } }],
			qualifiers: { platform_cpes: ["cpe:2.3:o:microsoft:windows:-:*:*:*:*:*:*:*"] },
		},
	} as (typeof windows.packages)[number];
	assert.deepEqual(rangeMatches([windows], jvm), []);
	assert.deepEqual(databaseSearchArguments("oracle:openjdk"), [
		"db",
		"search",
		"--pkg",
		"cpe:2.3:a:oracle:openjdk",
		"--limit",
		"0",
		"-o",
		"json",
	]);
});
