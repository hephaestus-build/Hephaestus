import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { parse } from "yaml";

import { asArray, asRecord } from "./lib/json.ts";
import {
	BRANCH,
	builderBuildpacks,
	buildpackDependencies,
	derivePin,
	pullRequestTitle,
	vendorBuildpack,
} from "./update-buildpack-pins.ts";

// Trimmed from paketo-buildpacks/bellsoft-liberica v11.10.5 buildpack.toml, plus a later build of line 25.
const jre = (build: string, arch: string, file: string, sha256: string) => `
  [[metadata.dependencies]]
    cpes = ["cpe:2.3:a:oracle:jre:25.0.5:*:*:*:*:*:*:*"]
    id = "jre"
    name = "BellSoft Liberica JRE"
    purl = "pkg:generic/bellsoft-jre@25.0.5?arch=${arch}"
    sha256 = "${sha256}"
    stacks = ["*"]
    uri = "https://github.com/bell-sw/Liberica/releases/download/${build}/bellsoft-jre${build}-linux-${file}.tar.gz"
    version = "${build.split(".").slice(0, 3).join(".")}"

    [[metadata.dependencies.licenses]]
      type = "GPL-2.0 WITH Classpath-exception-2.0"
      uri = "https://openjdk.java.net/legal/gplv2+ce.html"
`;
const toml = `api = "0.7"
[metadata]
${jre("25.0.5.0.3+5", "amd64", "amd64", "a".repeat(64))}
${jre("25.0.5.0.3+5", "arm64", "aarch64", "b".repeat(64))}
${jre("25.0.5.0.3+5", "ppc64le", "ppc64le", "c".repeat(64))}
${jre("21.0.13.0.3+5", "amd64", "amd64", "d".repeat(64))}
  [[metadata.dependencies]]
    id = "jdk"
    purl = "pkg:generic/bellsoft-jdk@25.0.5?arch=amd64"
    sha256 = "${"e".repeat(64)}"
    uri = "https://github.com/bell-sw/Liberica/releases/download/25.0.5.0.3+5/bellsoft-jdk25.0.5.0.3+5-linux-amd64.tar.gz"
    version = "25.0.5"
`;

void test("a buildpack.toml yields each dependency with its architecture", () => {
	const dependencies = buildpackDependencies(toml);
	assert.equal(dependencies.length, 5);
	assert.deepEqual(dependencies[0], {
		id: "jre",
		version: "25.0.5",
		uri: "https://github.com/bell-sw/Liberica/releases/download/25.0.5.0.3+5/bellsoft-jre25.0.5.0.3+5-linux-amd64.tar.gz",
		sha256: "a".repeat(64),
		arch: "amd64",
	});
	assert.throws(
		() => buildpackDependencies('[[metadata.dependencies]]\n    id = "jre"\n'),
		/lacks its id, version, uri or sha256/u,
	);
});

void test("the JRE pin keeps the build number that the release directory names", () => {
	assert.deepEqual(derivePin(buildpackDependencies(toml), "jre", "25"), {
		version: "25.0.5.0.3_5",
		url: "https://github.com/bell-sw/Liberica/releases/download/25.0.5.0.3+5/",
		assets: {
			"linux-x64": { name: "bellsoft-jre25.0.5.0.3+5-linux-amd64.tar.gz", sha256: "a".repeat(64) },
			"linux-arm64": {
				name: "bellsoft-jre25.0.5.0.3+5-linux-aarch64.tar.gz",
				sha256: "b".repeat(64),
			},
		},
	});
	assert.throws(() => derivePin(buildpackDependencies(toml), "jre", "27"), /no jre of line 27/u);
});

void test("a library without an architecture is pinned for all", () => {
	const library = `[[metadata.dependencies]]
    id = "spring-cloud-bindings"
    purl = "pkg:generic/springframework/spring-cloud-bindings@2.0.0"
    sha256 = "${"f".repeat(64)}"
    uri = "https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-bindings/2.0.4/spring-cloud-bindings-2.0.4.jar"
    version = "2.0.4"
[[metadata.dependencies]]
    id = "spring-cloud-bindings"
    sha256 = "${"0".repeat(64)}"
    uri = "https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-bindings/1.13.0/spring-cloud-bindings-1.13.0.jar"
    version = "1.13.0"
`;
	assert.deepEqual(derivePin(buildpackDependencies(library), "spring-cloud-bindings", "2"), {
		version: "2.0.4",
		url: "https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-bindings/2.0.4/",
		assets: { all: { name: "spring-cloud-bindings-2.0.4.jar", sha256: "f".repeat(64) } },
	});
});

void test("the builder metadata names each buildpack's version", () => {
	const versions = builderBuildpacks({
		buildpacks: [{ id: "paketo-buildpacks/bellsoft-liberica", version: "11.10.5" }],
	});
	assert.equal(versions.get("paketo-buildpacks/bellsoft-liberica"), "11.10.5");
	assert.equal(
		pullRequestTitle("25.0.5.1_1"),
		"fix(deps): the server image runs the JRE 25.0.5.1_1",
	);
});

void test("a Temurin pin keeps the build of its release directory, and its buildpack names its repository", () => {
	// From paketo-buildpacks/adoptium v12.7.4 buildpack.toml.
	const adoptium = `[[metadata.dependencies]]
    id = "jre"
    purl = "pkg:generic/adoptium-jre@25.0.5?arch=amd64"
    sha256 = "6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6"
    uri = "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_linux_hotspot_25.0.5_8.tar.gz"
    version = "25.0.5"
`;
	assert.deepEqual(derivePin(buildpackDependencies(adoptium), "jre", "25"), {
		version: "25.0.5_8",
		url: "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/",
		assets: {
			"linux-x64": {
				name: "OpenJDK25U-jre_x64_linux_hotspot_25.0.5_8.tar.gz",
				sha256: "6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6",
			},
		},
	});
	assert.deepEqual(vendorBuildpack(`docker://paketobuildpacks/adoptium@sha256:${"a".repeat(64)}`), {
		image: "paketobuildpacks/adoptium",
		repository: "paketo-buildpacks/adoptium",
	});
	assert.throws(
		() => vendorBuildpack("docker://example.com/jvm@sha256:1"),
		/outside paketobuildpacks/u,
	);
});

void test("only a checkout-free job that runs one gh command may dispatch CI/CD on the JRE pull request", () => {
	const jobs = asRecord(
		asRecord(parse(readFileSync(".github/workflows/jvm-watch.yml", "utf8")), "jvm-watch").jobs,
		"jobs",
	);
	const update = asRecord(jobs["jre-update"], "jre-update");
	assert.equal(asRecord(update.permissions, "jre-update permissions").actions, undefined);
	const dispatch = asRecord(jobs["dispatch-ci"], "dispatch-ci");
	assert.equal(dispatch.needs, "jre-update");
	assert.equal(dispatch["runs-on"], "ubuntu-slim");
	assert.deepEqual(dispatch.permissions, { actions: "write" });
	const steps = asArray(dispatch.steps, "dispatch-ci steps").map((step) => asRecord(step, "step"));
	assert.equal(steps.length, 1);
	assert.equal(steps[0]?.uses, undefined);
	assert.equal(steps[0]?.run, `gh workflow run cicd.yml --ref ${BRANCH}`);
});
