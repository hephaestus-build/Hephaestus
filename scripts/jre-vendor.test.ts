import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { jvmBuildpack, versionBelow, withJvmBuildpack } from "./lib/buildpack-descriptor.ts";

const descriptor = readFileSync("server/application/project.toml", "utf8");
// The pin of the Temurin evidence build of 2026-11-18: Paketo Adoptium 12.7.4 and the JRE that its
// buildpack.toml declares. That image passed the inventory and the class and Metaspace checks.
const temurin = {
	buildpack:
		"docker://paketobuildpacks/adoptium@sha256:a53eceeb9acd3ebf10f715a7b0a3af4dcf936df68db1122c960b06d9766fcdfa",
	version: "25.0.5_8",
	url: "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/",
	assets: {
		"linux-x64": {
			name: "OpenJDK25U-jre_x64_linux_hotspot_25.0.5_8.tar.gz",
			sha256: "6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6",
		},
		"linux-arm64": {
			name: "OpenJDK25U-jre_aarch64_linux_hotspot_25.0.5_8.tar.gz",
			sha256: "963931723891302ebb2687178532c3d793d71e2577cd058061c7efb396140a86",
		},
	},
};

const groups = (toml: string) =>
	[...toml.matchAll(/^\[\[io\.buildpacks\.group\]\]\n(?<entry>(?:id|uri) = "[^"]+")$/gmu)].map(
		(match) => match.groups?.entry,
	);

void test("the builder's Liberica needs no change to the descriptor", () => {
	const pin: unknown = JSON.parse(readFileSync(".github/ci-tools/buildpack-jre.json", "utf8"));
	assert.equal(withJvmBuildpack(descriptor, jvmBuildpack(pin)), descriptor);
});

void test("a pin that names another JVM buildpack puts it before paketo-buildpacks/java, and changes nothing else", () => {
	const built = withJvmBuildpack(descriptor, jvmBuildpack(temurin));
	assert.deepEqual(groups(built), [`uri = "${temurin.buildpack}"`, ...groups(descriptor)]);
	assert.equal(
		built.replace(`[[io.buildpacks.group]]\nuri = "${temurin.buildpack}"\n\n`, ""),
		descriptor,
	);
});

void test("a JVM buildpack that is not pinned by digest is refused", () => {
	assert.throws(
		() => withJvmBuildpack(descriptor, "docker://paketobuildpacks/adoptium:latest"),
		/pinned by digest/u,
	);
	assert.throws(() => jvmBuildpack({ buildpack: 7 }), /must be a string/u);
});

void test("the mitigation gate reads a Temurin version as it reads a Liberica one", () => {
	assert.equal(versionBelow(temurin.version, [25, 0, 5, 1]), true);
	assert.equal(versionBelow("25.0.6_7", [25, 0, 5, 1]), false);
});
