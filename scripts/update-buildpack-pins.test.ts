import assert from "node:assert/strict";
import { test } from "node:test";

import {
	builderBuildpacks,
	buildpackDependencies,
	derivePin,
	pullRequestTitle,
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
		"fix(deps): the server image runs the Liberica JRE 25.0.5.1_1",
	);
});
