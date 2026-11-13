import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	ContextFolder,
	type FolderServer,
	type LoadedArchive,
} from "../../../main/resources/agent/context-folder.ts";
import type { SandboxRefusal } from "../../../main/resources/agent/heph-host-protocol.ts";
import { archiveOf, tarOf } from "./turn-worker.ts";

function loaded(
	files: Readonly<Record<string, string>>,
	overrides: Partial<LoadedArchive> = {},
): LoadedArchive {
	return {
		ok: true,
		path: "repos/github.com/acme/api",
		resolved: { ref: "main", sha: "7044a8a0c0ffee0c0ffee0c0ffee0c0ffee0c0ff", cite: "@7044a8a" },
		note: "Loaded the default branch of acme/api.",
		summary: [],
		archive: archiveOf(tarOf(files)),
		cancel: async () => undefined,
		...overrides,
	};
}

function server(
	documents: Readonly<Record<string, string>>,
	load: (path: string) => Promise<LoadedArchive | SandboxRefusal> = async () =>
		loaded({ "README.md": "api\n" }),
): FolderServer & { documentsAsked: number } {
	const answer = {
		documentsAsked: 0,
		documents: async () => {
			answer.documentsAsked += 1;
			return tarOf(documents);
		},
		document: async () => null,
		load: async (requested: string) => load(requested),
	};
	return answer;
}

function folderIn(fake: FolderServer): { folder: ContextFolder; root: string; close: () => void } {
	const root = mkdtempSync(path.join(tmpdir(), "context-folder-"));
	return {
		folder: new ContextFolder(root, fake),
		root,
		close: () => {
			execFileSync("chmod", ["-R", "u+w", root]);
			rmSync(root, { recursive: true, force: true });
		},
	};
}

const DOCUMENTS = {
	"INDEX.md": "# Index\n",
	"FIELDS.md": "# Fields\n",
	"inputs/context/a.json": "{}\n",
};
const { signal } = new AbortController();

await test("a turn's folder holds its documents once, and an earlier turn's files are gone", async () => {
	const fake = server(DOCUMENTS);
	const { folder, root, close } = folderIn(fake);
	try {
		mkdirSync(path.join(root, "repos/old"), { recursive: true });
		writeFileSync(path.join(root, "repos/old/stale.txt"), "from an earlier turn");
		writeFileSync(path.join(root, "notes.txt"), "scratch from an earlier turn");

		assert.equal(await folder.prepare(signal), undefined);
		assert.equal(await folder.prepare(signal), undefined);

		assert.equal(fake.documentsAsked, 1, "the documents are fetched once for the turn");
		assert.equal(readFileSync(path.join(root, "INDEX.md"), "utf8"), "# Index\n");
		assert.equal(readFileSync(path.join(root, "inputs/context/a.json"), "utf8"), "{}\n");
		assert.throws(() => statSync(path.join(root, "repos/old")), { code: "ENOENT" });
		assert.throws(() => statSync(path.join(root, "notes.txt")), { code: "ENOENT" });
	} finally {
		close();
	}
});

await test("a folder whose documents cannot be fetched refuses once, and the next tool tries again", async () => {
	let fail = true;
	const fake: FolderServer = {
		documents: async () => {
			if (fail) {
				fail = false;
				throw new Error("the worker did not answer");
			}
			return tarOf(DOCUMENTS);
		},
		document: async () => null,
		load: async () => loaded({}),
	};
	const { folder, root, close } = folderIn(fake);
	try {
		assert.deepEqual(await folder.prepare(signal), {
			ok: false,
			error: "UNAVAILABLE",
			detail: "The context folder could not be prepared. Try again once.",
		});
		assert.equal(await folder.prepare(signal), undefined);
		assert.equal(readFileSync(path.join(root, "FIELDS.md"), "utf8"), "# Fields\n");
	} finally {
		close();
	}
});

await test("a load is extracted read-only under its path, summarized there, and replaced by a second load", async () => {
	let version = 1;
	const fake = server(DOCUMENTS, async () =>
		loaded(
			{ "README.md": `version ${version}\n`, [`only-in-${version}.txt`]: "x" },
			{ summary: ["sh", "-c", String.raw`ls | sort | tr '\n' ' '`] },
		),
	);
	const { folder, root, close } = folderIn(fake);
	try {
		const first = await folder.load({ path: "repos/github.com/acme/api" }, signal);
		assert.ok(first.ok);
		assert.equal(first.note, "Loaded the default branch of acme/api.\nREADME.md only-in-1.txt");
		const dir = path.join(root, "repos/github.com/acme/api");
		assert.equal(readFileSync(path.join(dir, "README.md"), "utf8"), "version 1\n");
		// oxlint-disable-next-line no-bitwise -- a file mode is a bit set
		assert.equal(statSync(path.join(dir, "README.md")).mode & 0o222, 0, "no write bit");

		version = 2;
		const second = await folder.load({ path: "repos/github.com/acme/api" }, signal);
		assert.ok(second.ok);
		assert.equal(readFileSync(path.join(dir, "README.md"), "utf8"), "version 2\n");
		assert.throws(() => statSync(path.join(dir, "only-in-1.txt")), { code: "ENOENT" });
	} finally {
		close();
	}
});

await test("a load whose archive did not arrive whole is refused and leaves nothing", async () => {
	const bytes = tarOf({ "README.md": "api\n" });
	const fake = server(DOCUMENTS, async () =>
		loaded({}, { archive: archiveOf(bytes, "0".repeat(64)) }),
	);
	const { folder, root, close } = folderIn(fake);
	try {
		assert.deepEqual(await folder.load({ path: "repos/github.com/acme/api" }, signal), {
			ok: false,
			error: "UNAVAILABLE",
			detail: "The context folder could not be prepared. Try again once.",
		});
		assert.throws(() => statSync(path.join(root, "repos/github.com/acme/api/README.md")), {
			code: "ENOENT",
		});
	} finally {
		close();
	}
});

await test("a load path that leaves the folder is refused before anything is written, and its archive released", async () => {
	let released = false;
	const fake = server(DOCUMENTS, async () =>
		loaded(
			{ "x.txt": "x" },
			{
				path: "../outside",
				cancel: async () => {
					released = true;
				},
			},
		),
	);
	const { folder, root, close } = folderIn(fake);
	try {
		const answer = await folder.load({ path: "repos/../../outside" }, signal);
		assert.equal(answer.ok, false);
		assert.ok(released);
		assert.throws(() => statSync(path.join(path.dirname(root), "outside")), { code: "ENOENT" });
	} finally {
		close();
	}
});

await test("a refused load is the server's answer, and the folder is still prepared", async () => {
	const refused: SandboxRefusal = {
		ok: false,
		error: "NOT_VISIBLE",
		detail: "You cannot read that repository.",
	};
	const fake = server(DOCUMENTS, async () => refused);
	const { folder, root, close } = folderIn(fake);
	try {
		assert.deepEqual(await folder.load({ path: "repos/github.com/acme/secret" }, signal), refused);
		assert.equal(readFileSync(path.join(root, "INDEX.md"), "utf8"), "# Index\n");
	} finally {
		close();
	}
});

await test("indexed item documents are staged whole and later reads use the local file", async () => {
	const asked: string[] = [];
	const bytes = Buffer.from('{"title":"The complete item"}\n');
	const fake: FolderServer = {
		...server(DOCUMENTS),
		document: async (requested) => {
			asked.push(requested);
			return bytes;
		},
	};
	const { folder, root, close } = folderIn(fake);
	try {
		await folder.prepare(signal);
		const item = "inputs/context/scm/item.json";
		await Promise.all([folder.document(item, signal), folder.document(item, signal)]);
		assert.deepEqual(readFileSync(path.join(root, item)), bytes);
		// oxlint-disable-next-line no-bitwise -- a file mode is a bit set
		assert.equal(statSync(path.join(root, item)).mode & 0o222, 0);
		const fetches = asked.length;
		await folder.document(item, signal);
		await folder.document("../outside.json", signal);
		await folder.document("repos/source.json", signal);
		assert.equal(asked.length, fetches);
		assert.ok(asked.every((requested) => requested === item));
	} finally {
		close();
	}
});

await test("an item the server cannot supply remains absent", async () => {
	const { folder, root, close } = folderIn(server(DOCUMENTS));
	try {
		await folder.prepare(signal);
		await folder.document("inputs/context/missing.json", signal);
		assert.throws(() => statSync(path.join(root, "inputs/context/missing.json")), {
			code: "ENOENT",
		});
	} finally {
		close();
	}
});
