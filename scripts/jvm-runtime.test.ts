import assert from "node:assert/strict";
import { test } from "node:test";

import { readJsonFileSync } from "./lib/json.ts";
import {
	assertRuntimeInventory,
	isPinnedBuild,
	JVM_RESULT_TYPE,
	jvmPackages,
	jvmResult,
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
