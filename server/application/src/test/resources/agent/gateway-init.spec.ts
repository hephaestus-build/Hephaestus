import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { once } from "node:events";
import { statSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

import {
	downloadWorkspace,
	extract,
	extractArguments,
} from "../../../main/resources/agent/gateway-init.ts";

/** The sandbox token and the run's claim, as main() sends them with every gateway call. */
const HEADERS = {
	Authorization: "Bearer attempt-token",
	"x-agent-run": "run-1",
	"x-agent-epoch": "2",
};

void test("the initializer refuses to start without the run's claim, before any gateway call", () => {
	const child = spawnSync(
		process.execPath,
		[fileURLToPath(new URL("../../../main/resources/agent/gateway-init.ts", import.meta.url))],
		{
			env: {
				PATH: process.env.PATH,
				SANDBOX_RUNTIME_URL: "http://gateway.invalid/runtime",
				LLM_PROXY_TOKEN: "attempt-token",
				SANDBOX_WORK_DEADLINE_MS: String(Date.now() + 60_000),
			},
			encoding: "utf8",
		},
	);
	assert.equal(child.status, 1);
	assert.match(child.stderr, /LLM_PROXY_CLAIM is missing/u);
});

void test("retries a truncated workspace from byte zero", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	const workspace = Buffer.from("complete workspace archive");
	let requests = 0;
	const server = createServer((request, response) => {
		// Another local process can probe a newly opened port. It carries no credential.
		if (
			request.headers.authorization !== "Bearer attempt-token" ||
			request.headers["x-agent-run"] !== "run-1" ||
			request.headers["x-agent-epoch"] !== "2"
		) {
			response.writeHead(401).end();
			return;
		}
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
		await downloadWorkspace(
			`http://127.0.0.1:${address.port}/runtime`,
			HEADERS,
			archive,
			AbortSignal.timeout(30_000),
		);
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

void test("waits out a limit of the gateway on discovery and on the download", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	const workspace = Buffer.from("workspace");
	let discoveries = 0;
	let downloads = 0;
	const server = createServer((request, response) => {
		if (request.url === "/runtime") {
			discoveries += 1;
			if (discoveries === 1) {
				response.writeHead(429, { "retry-after": "0" }).end();
				return;
			}
			response.end(JSON.stringify({ protocolVersion: 3, workspaceByteBudget: 1024 }));
			return;
		}
		if (request.url !== "/runtime/workspace") {
			response.writeHead(404).end();
			return;
		}
		downloads += 1;
		if (downloads === 1) {
			response.writeHead(429, { "retry-after": "0" }).end();
			return;
		}
		response.writeHead(200, { "content-length": String(workspace.length) }).end(workspace);
	});
	try {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		const archive = path.join(directory, "workspace.tar");
		await downloadWorkspace(
			`http://127.0.0.1:${address.port}/runtime`,
			HEADERS,
			archive,
			AbortSignal.timeout(30_000),
		);
		assert.equal(discoveries, 2);
		assert.equal(downloads, 2);
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
		// Another local process can probe a newly opened port. Only the workspace requests count.
		if (request.url !== "/runtime/workspace") {
			response.writeHead(404).end();
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
				HEADERS,
				path.join(directory, "workspace.tar"),
				AbortSignal.timeout(30_000),
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

void test("rejects a compressed workspace whose decoded bytes exceed the budget, without retry", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	// The transfer fits the budget; only the decoded stream exceeds it.
	const compressed = gzipSync(Buffer.alloc(64 * 1024));
	let requests = 0;
	const server = createServer((request, response) => {
		if (request.url === "/runtime") {
			response.end(JSON.stringify({ protocolVersion: 3, workspaceByteBudget: 1024 }));
			return;
		}
		// Another local process can probe a newly opened port. Only the workspace requests count.
		if (request.url !== "/runtime/workspace") {
			response.writeHead(404).end();
			return;
		}
		requests += 1;
		response.writeHead(200, { "Content-Encoding": "gzip", "Content-Length": compressed.length });
		response.end(compressed);
	});
	try {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		assert.ok(compressed.length < 1024);
		await assert.rejects(
			downloadWorkspace(
				`http://127.0.0.1:${address.port}/runtime`,
				HEADERS,
				path.join(directory, "workspace.tar"),
				AbortSignal.timeout(30_000),
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
		// Another local process can probe a newly opened port. Only the workspace requests count.
		if (request.url !== "/runtime/workspace") {
			response.writeHead(404).end();
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
				HEADERS,
				path.join(directory, "workspace.tar"),
				AbortSignal.timeout(30_000),
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

/**
 * The skip of a test whose host lacks `missing`. CI must run it, so there the test runs and fails on the
 * missing precondition; elsewhere the skip is announced, so that it cannot pass unnoticed.
 */
function skipUnless(missing: string | false): string | false {
	if (missing === false || process.env.CI === "true") {
		return false;
	}
	console.log(`::warning::gateway-init extraction test skipped: ${missing}`);
	return missing;
}

/** Fails a test that CI runs on a host without its precondition. */
function requirePrecondition(missing: string | false): void {
	if (missing !== false) {
		assert.fail(`CI must run this test on a Linux runner as a non-root user: ${missing}`);
	}
}

const noGnuTar = gnuTar ? false : "the sandbox image extracts with GNU tar, which this host lacks";

void test(
	"extracts into a sandbox user's own workspace whose directories exist already, as on Docker's tmpfs",
	{ skip: skipUnless(noGnuTar) },
	async () => {
		requirePrecondition(noGnuTar);
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

function notOwnedMissing(): string | false {
	if (!gnuTar) {
		return noGnuTar;
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
	{ skip: skipUnless(notOwnedMissing()) },
	async () => {
		requirePrecondition(notOwnedMissing());
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
