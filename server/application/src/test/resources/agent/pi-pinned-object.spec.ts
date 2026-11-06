// A pinned file read through an untrusted source: the walk against real Git objects, and against a source that lies.

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { readPinnedBlob } from "../../../main/resources/agent/pi-change.ts";
import {
	HISTORY_COMMITS,
	type ObjectSource,
	PinnedHistory,
	readPinnedObject,
} from "../../../main/resources/agent/pi-pinned-object.ts";

const repository = mkdtempSync(path.join(tmpdir(), "pinned-object-"));
const git = (...args: string[]) =>
	execFileSync("git", ["-C", repository, ...args], { encoding: "buffer" });
git("init", "-q");
git("config", "user.email", "fixture@example.invalid");
git("config", "user.name", "fixture");
mkdirSync(path.join(repository, "src/deep"), { recursive: true });
writeFileSync(path.join(repository, "src/deep/kept.ts"), "export const kept = 1;\n");
writeFileSync(path.join(repository, "README.md"), "# readme\n");
writeFileSync(path.join(repository, "large.bin"), "x".repeat(4096));
symlinkSync("README.md", path.join(repository, "link"));
writeFileSync(path.join(repository, "run.sh"), "#!/bin/sh\n", { mode: 0o755 });
git("add", ".");
git("commit", "-qm", "fixture");
const commit = git("rev-parse", "HEAD").toString().trim();
writeFileSync(path.join(repository, "README.md"), "# readme, changed\n");
git("commit", "-qam", "second");
const head = git("rev-parse", "HEAD").toString().trim();

const objectId = (type: string, content: Buffer) =>
	createHash("sha1").update(`${type} ${content.length}\0`).update(content).digest("hex");

/** What the capture pinned. */
const pinned = (source: ObjectSource) => new PinnedHistory(source, [head]);

/** The real objects of the fixture, as an honest tools side serves them. */
const honest: ObjectSource = {
	read: async (type, id, limit) => {
		const content = git("cat-file", type, id);
		return content.length > limit ? null : content;
	},
	size: async (id) => Number(git("cat-file", "-s", id).toString()),
};

for (const file of [
	"README.md",
	"src/deep/kept.ts",
	"run.sh",
	"large.bin",
	"link",
	"src",
	"gone.ts",
	"src/deep/gone",
]) {
	void test(`the walk agrees with Git at ${file}`, async () => {
		assert.deepEqual(
			await readPinnedObject(honest, pinned(honest), commit, file, 1_048_576),
			readPinnedBlob(repository, commit, file, 1_048_576),
		);
	});
}

void test("a file past the limit is named as too large, and is not read", async () => {
	let read = false;
	const source: ObjectSource = {
		...honest,
		read: async (type, id, limit) => {
			read ||= type === "blob";
			return honest.read(type, id, limit);
		},
	};
	assert.deepEqual(await readPinnedObject(source, pinned(source), commit, "large.bin", 1000), {
		kind: "tooLarge",
		size: 4096,
	});
	assert.equal(read, false);
});

void test("a path that leaves the checkout, and an id that is not one, are unsafe", async () => {
	assert.deepEqual(await readPinnedObject(honest, pinned(honest), commit, "../secret", 100), {
		kind: "unsafe",
	});
	assert.deepEqual(await readPinnedObject(honest, pinned(honest), commit, ".git/config", 100), {
		kind: "unsafe",
	});
	assert.deepEqual(await readPinnedObject(honest, pinned(honest), "HEAD", "README.md", 100), {
		kind: "unsafe",
	});
});

for (const type of ["commit", "tree", "blob"] as const) {
	void test(`a forged ${type} reads as unreadable, never as its bytes`, async () => {
		const source: ObjectSource = {
			...honest,
			read: async (asked, id, limit) => {
				const real = await honest.read(asked, id, limit);
				return asked === type && real !== null
					? Buffer.concat([real, Buffer.from("forged")])
					: real;
			},
		};
		assert.deepEqual(
			await readPinnedObject(source, pinned(honest), commit, "src/deep/kept.ts", 1000),
			{
				kind: "unreadable",
			},
		);
	});
}

void test("another file's real blob does not pass for the one that the tree names", async () => {
	const other = git("rev-parse", `${commit}:README.md`).toString().trim();
	const source: ObjectSource = {
		...honest,
		read: async (type, id, limit) => honest.read(type, type === "blob" ? other : id, limit),
	};
	assert.deepEqual(
		await readPinnedObject(source, pinned(honest), commit, "src/deep/kept.ts", 1000),
		{
			kind: "unreadable",
		},
	);
});

void test("a source that lies about a size gains nothing", async () => {
	const small: ObjectSource = { ...honest, size: async () => 1 };
	assert.deepEqual(await readPinnedObject(small, pinned(honest), commit, "large.bin", 100), {
		kind: "unreadable",
	});
	const huge: ObjectSource = { ...honest, size: async () => 1_073_741_824 };
	const claimed = await readPinnedObject(huge, pinned(honest), commit, "README.md", 100);
	assert.equal(claimed.kind, "tooLarge");
});

void test("a source that refuses reads as unreadable", async () => {
	const silent: ObjectSource = { read: async () => null, size: async () => null };
	assert.deepEqual(await readPinnedObject(silent, pinned(silent), commit, "README.md", 100), {
		kind: "unreadable",
	});
});

/** A source that also holds objects the tools side made up, each with its true id. */
function withForged(objects: Map<string, Buffer>): ObjectSource {
	return {
		read: async (type, id, limit) => objects.get(id) ?? honest.read(type, id, limit),
		size: async (id) => objects.get(id)?.length ?? honest.size(id),
	};
}

/** A commit of `tree` with a real hash, which no pinned commit reaches. */
function forgedCommit(objects: Map<string, Buffer>, tree: Buffer): string {
	const treeId = objectId("tree", tree);
	objects.set(treeId, tree);
	const commitBytes = Buffer.from(
		`tree ${treeId}\nauthor a <a@b> 0 +0000\ncommitter a <a@b> 0 +0000\n\nmade up\n`,
	);
	const id = objectId("commit", commitBytes);
	objects.set(id, commitBytes);
	return id;
}

void test("a commit made up with a true hash is refused, though every object in it checks out", async () => {
	const objects = new Map<string, Buffer>();
	const blob = Buffer.from("a claim about the work that the commit never held\n");
	const blobId = objectId("blob", blob);
	objects.set(blobId, blob);
	const tree = Buffer.concat([Buffer.from("100644 README.md\0"), Buffer.from(blobId, "hex")]);
	const forged = forgedCommit(objects, tree);
	const source = withForged(objects);
	assert.deepEqual(await readPinnedObject(source, pinned(source), forged, "README.md", 1000), {
		kind: "unreadable",
	});
});

void test("a parent of the pinned commit is reached, and the pinned commit itself", async () => {
	const history = pinned(honest);
	assert.equal(await history.contains(head), true);
	assert.equal(await history.contains(commit), true);
	const page = await readPinnedObject(honest, history, head, "README.md", 1000);
	assert.equal(page.kind === "regular" && page.bytes.toString(), "# readme, changed\n");
});

void test("a commit past the walk's bound reads as unreadable, and the walk stops there", async () => {
	const objects = new Map<string, Buffer>();
	let tip = "";
	let parent = "";
	for (let at = 0; at <= HISTORY_COMMITS + 1; at += 1) {
		const bytes = Buffer.from(`tree ${"0".repeat(40)}\n${parent}\nmessage ${at}\n`);
		tip = objectId("commit", bytes);
		objects.set(tip, bytes);
		parent = `parent ${tip}`;
	}
	let reads = 0;
	const source: ObjectSource = {
		read: async (_type, id) => {
			reads += 1;
			return objects.get(id) ?? null;
		},
		size: async () => null,
	};
	assert.equal(await new PinnedHistory(source, [tip]).contains("a".repeat(40)), false);
	assert.equal(reads, HISTORY_COMMITS);
});

void test("a tree whose hash is right but whose bytes are malformed reads as unreadable", async () => {
	const objects = new Map<string, Buffer>();
	const forged = forgedCommit(objects, Buffer.from("100644 name-with-no-end"));
	const source = withForged(objects);
	const history = new PinnedHistory(source, [forged]);
	assert.deepEqual(await readPinnedObject(source, history, forged, "name", 1000), {
		kind: "unreadable",
	});
});
