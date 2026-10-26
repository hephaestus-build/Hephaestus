import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { once } from "node:events";
import { statSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { downloadWorkspace, extract, extractArguments } from "./gateway-init.ts";

void test("retries a truncated workspace from byte zero", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	const workspace = Buffer.from("complete workspace archive");
	let requests = 0;
	const server = createServer((request, response) => {
		assert.equal(request.headers.authorization, "Bearer attempt-token");
		if (request.url === "/runtime") {
			response.setHeader("Content-Type", "application/json");
			response.end(
				JSON.stringify({ protocolVersion: 3, workspaceByteBudget: workspace.length + 1024 }),
			);
			return;
		}
		assert.equal(request.url, "/runtime/workspace");
		requests += 1;
		if (requests === 1) {
			response.writeHead(200, { "Content-Length": workspace.length });
			response.write(workspace.subarray(0, 4));
			setImmediate(() => {
				response.destroy();
			});
		} else {
			response.end(workspace);
		}
	});
	try {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		const archive = path.join(directory, "workspace.tar");
		await downloadWorkspace(`http://127.0.0.1:${address.port}/runtime`, "attempt-token", archive);
		assert.equal(requests, 2);
		assert.deepEqual(await readFile(archive), workspace);
	} finally {
		const closed = once(server, "close");
		server.close();
		server.closeAllConnections();
		await closed;
		await rm(directory, { recursive: true, force: true });
	}
});

void test("rejects an archive above the advertised budget without retry", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	let requests = 0;
	const server = createServer((request, response) => {
		if (request.url === "/runtime") {
			response.end(JSON.stringify({ protocolVersion: 3, workspaceByteBudget: 2 }));
			return;
		}
		requests += 1;
		response.end("too large");
	});
	try {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		await assert.rejects(
			downloadWorkspace(
				`http://127.0.0.1:${address.port}/runtime`,
				"attempt-token",
				path.join(directory, "workspace.tar"),
			),
			/Workspace exceeded its advertised byte budget/u,
		);
		assert.equal(requests, 1);
	} finally {
		const closed = once(server, "close");
		server.close();
		server.closeAllConnections();
		await closed;
		await rm(directory, { recursive: true, force: true });
	}
});

void test("rejects a workspace without an exact transfer length without retry", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	let requests = 0;
	const server = createServer((request, response) => {
		if (request.url === "/runtime") {
			response.end(JSON.stringify({ protocolVersion: 3, workspaceByteBudget: 1024 }));
			return;
		}
		requests += 1;
		response.writeHead(200, { "Transfer-Encoding": "chunked" });
		response.end("workspace");
	});
	try {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		await assert.rejects(
			downloadWorkspace(
				`http://127.0.0.1:${address.port}/runtime`,
				"attempt-token",
				path.join(directory, "workspace.tar"),
			),
			/Workspace requires a valid Content-Length/u,
		);
		assert.equal(requests, 1);
	} finally {
		const closed = once(server, "close");
		server.close();
		server.closeAllConnections();
		await closed;
		await rm(directory, { recursive: true, force: true });
	}
});

const gnuTar = spawnSync("tar", ["--version"], { encoding: "utf8" }).stdout.includes("GNU tar");

/** An archive of `entries` (relative paths to file contents) under `root`, with an entry for each directory. */
async function archiveOf(root: string, entries: Record<string, string>): Promise<string> {
	const source = await mkdtemp(path.join(tmpdir(), "gateway-extract-source-"));
	for (const [name, content] of Object.entries(entries)) {
		await mkdir(path.dirname(path.join(source, name)), { recursive: true });
		await writeFile(path.join(source, name), content);
	}
	const archive = path.join(
		await mkdtemp(path.join(tmpdir(), "gateway-extract-archive-")),
		"w.tar",
	);
	const created = spawnSync("tar", ["--create", "--file", archive, "--directory", source, root]);
	assert.equal(created.status, 0, String(created.stderr));
	await rm(source, { recursive: true, force: true });
	return archive;
}

void test(
	"extracts into a sandbox user's own workspace whose directories exist already, as on Docker's tmpfs",
	{ skip: gnuTar ? false : "the sandbox image extracts with GNU tar, which this host lacks" },
	async () => {
		const workspace = await mkdtemp(path.join(tmpdir(), "gateway-extract-workspace-"));
		try {
			await mkdir(path.join(workspace, "work"));
			await mkdir(path.join(workspace, ".pi"));
			const archive = await archiveOf(".", {
				"work/repository/README.md": "readme",
				// A directory of the repository that shares a mount point's name still extracts.
				"work/repository/work/nested.md": "nested",
				".pi/settings.json": "{}",
				"context/notes.md": "notes",
			});
			await extract(archive, workspace);
			assert.equal(
				await readFile(path.join(workspace, "work/repository/README.md"), "utf8"),
				"readme",
			);
			assert.equal(
				await readFile(path.join(workspace, "work/repository/work/nested.md"), "utf8"),
				"nested",
			);
			assert.equal(await readFile(path.join(workspace, ".pi/settings.json"), "utf8"), "{}");
			assert.equal(await readFile(path.join(workspace, "context/notes.md"), "utf8"), "notes");
		} finally {
			await rm(workspace, { recursive: true, force: true });
		}
	},
);

// /tmp belongs to root with mode 1777: a user may write into it but not change its mode or times, as the
// sandbox user with the emptyDir mount points that root owns in a Kubernetes review Pod.
const rootOwnedTmp = (() => {
	try {
		const tmp = statSync("/tmp");
		// The permission bits, sticky bit included, are the last four octal digits of the mode.
		return tmp.uid === 0 && tmp.mode.toString(8).endsWith("1777");
	} catch {
		return false;
	}
})();

function notOwnedSkip(): string | false {
	if (!gnuTar) {
		return "the sandbox image extracts with GNU tar, which this host lacks";
	}
	if (process.getuid?.() === 0) {
		return "root may change any directory, so no directory here is one that the test user does not own";
	}
	if (!rootOwnedTmp) {
		return "this host has no root-owned, world-writable /tmp to stand in for an emptyDir mount point";
	}
	return false;
}

void test(
	"extracts into directories that the sandbox user may write but does not own, as Kubernetes emptyDir mount points",
	{ skip: notOwnedSkip() },
	async () => {
		const unique = `gateway-extract-${process.pid}-${Date.now()}`;
		const archive = await archiveOf("tmp", { [`tmp/${unique}/work/README.md`]: "readme" });
		try {
			// /tmp stands in for a mount point. Fixture check: when tar extracts its entry, it cannot set the mode
			// and times of /tmp and exits 2, as on the emptyDir mount points.
			assert.equal(spawnSync("tar", extractArguments(archive, "/", [])).status, 2);
			await rm(`/tmp/${unique}`, { recursive: true, force: true });
			await extract(archive, "/", ["tmp"]);
			assert.equal(await readFile(`/tmp/${unique}/work/README.md`, "utf8"), "readme");
		} finally {
			await rm(`/tmp/${unique}`, { recursive: true, force: true });
		}
	},
);
