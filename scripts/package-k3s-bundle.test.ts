import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { gunzipSync } from "node:zlib";

import { packBundle, readMembers, tar } from "./package-k3s-bundle.ts";

const MTIME = 1_700_000_000;

function platform() {
	const directory = mkdtempSync(path.join(tmpdir(), "k3s-bundle-"));
	// Written out of order, so that the sort is what orders the members.
	for (const [name, contents] of [
		["k3s-values.yaml", "values: 1\n"],
		["k3s-install.sh", "#!/bin/sh\necho installed\n"],
		["k3s-config.yaml", "x".repeat(1500)],
	]) {
		writeFileSync(path.join(directory, name ?? ""), contents ?? "", { mode: 0o600 });
	}
	return directory;
}

void test("two builds of the same files are byte-identical, with a fixed header", (t) => {
	const directory = platform();
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const first = packBundle(directory, MTIME);
	writeFileSync(path.join(directory, "k3s-values.yaml"), "values: 1\n");
	assert.deepEqual(packBundle(directory, MTIME), first);
	// Magic, deflate, no flags (so no name), a time of 0.
	assert.deepEqual([...first.subarray(0, 8)], [0x1f, 0x8b, 8, 0, 0, 0, 0, 0]);
	assert.notDeepEqual(packBundle(directory, MTIME + 1), first);
});

void test("the tar bytes of fixed files are the same on every host", (t) => {
	const directory = platform();
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	// The compressed bytes follow the zlib of the Node.js that packs them; the tar bytes do not.
	assert.equal(
		createHash("sha256")
			.update(tar(readMembers(directory), MTIME))
			.digest("hex"),
		"60d0aba271af6870a046bedc8fc9b8e4cebd39359f6fe58dde301094840d9c1f",
	);
});

void test("a file without a k3s- or hephaestus- prefix stops the build", (t) => {
	const directory = platform();
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	writeFileSync(path.join(directory, "site.yaml"), "host: example.org\n");
	assert.throws(
		() => packBundle(directory, MTIME),
		/site\.yaml has no k3s- or hephaestus- prefix/u,
	);
});

void test("the members are sorted, owned by 0:0 and have fixed modes and times", (t) => {
	const directory = platform();
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const archive = gunzipSync(packBundle(directory, MTIME));
	const members: string[] = [];
	for (let offset = 0; archive[offset] !== 0;) {
		const field = (start: number, width: number) =>
			archive.toString("ascii", offset + start, offset + start + width).replace(/\0.*$/su, "");
		const size = Number.parseInt(field(124, 12), 8);
		assert.equal(field(108, 8), "0000000");
		assert.equal(field(116, 8), "0000000");
		assert.equal(Number.parseInt(field(136, 12), 8), MTIME);
		assert.equal(field(257, 6), "ustar");
		members.push(`${field(0, 100)} ${field(100, 8)} ${size}`);
		offset += 512 + Math.ceil(size / 512) * 512;
	}
	assert.deepEqual(members, [
		"k3s-config.yaml 0000644 1500",
		"k3s-install.sh 0000755 25",
		"k3s-values.yaml 0000644 10",
	]);
});

void test("tar lists and unpacks the bundle", { skip: process.platform === "win32" }, (t) => {
	const directory = platform();
	const work = mkdtempSync(path.join(tmpdir(), "k3s-bundle-out-"));
	t.after(() => {
		rmSync(directory, { recursive: true, force: true });
		rmSync(work, { recursive: true, force: true });
	});
	const file = path.join(work, "hephaestus-k3s-0.0.0.tar.gz");
	writeFileSync(file, packBundle(directory, MTIME));
	const listing = spawnSync("tar", ["-tzf", file], { encoding: "utf8" });
	assert.equal(listing.status, 0, listing.stderr);
	assert.equal(listing.stdout, "k3s-config.yaml\nk3s-install.sh\nk3s-values.yaml\n");
	const unpacked = path.join(work, "unpacked");
	mkdirSync(unpacked);
	const extraction = spawnSync("tar", ["-xzf", file, "-C", unpacked], { encoding: "utf8" });
	assert.equal(extraction.status, 0, extraction.stderr);
	assert.equal(
		readFileSync(path.join(unpacked, "k3s-install.sh"), "utf8"),
		"#!/bin/sh\necho installed\n",
	);
});

void test(
	"the bundle of the repository holds every file of deploy/platform",
	{
		skip: process.platform === "win32",
	},
	(t) => {
		const platformDirectory = path.join(import.meta.dirname, "../deploy/platform");
		const work = mkdtempSync(path.join(tmpdir(), "k3s-bundle-repository-"));
		t.after(() => rmSync(work, { recursive: true, force: true }));
		const file = path.join(work, "bundle.tar.gz");
		writeFileSync(file, packBundle(platformDirectory, MTIME));
		const listing = spawnSync("tar", ["-tzf", file], { encoding: "utf8" });
		assert.equal(listing.status, 0, listing.stderr);
		assert.deepEqual(
			listing.stdout.split("\n").slice(0, -1),
			readdirSync(platformDirectory).toSorted(),
		);
		assert.ok(listing.stdout.includes("k3s-install.sh\n"));
	},
);

void test("a directory in the platform folder stops the build", (t) => {
	const directory = platform();
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	mkdirSync(path.join(directory, "nested"));
	assert.throws(() => packBundle(directory, MTIME), /regular files only/u);
});
