import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { downloadWorkspace } from "./gateway-init.ts";

void test("retries a truncated workspace from byte zero", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-download-test-"));
	const workspace = Buffer.from("complete workspace archive");
	let requests = 0;
	const server = createServer((request, response) => {
		assert.equal(request.headers.authorization, "Bearer attempt-token");
		if (request.url === "/runtime") {
			response.setHeader("Content-Type", "application/json");
			response.end(JSON.stringify({ protocolVersion: 3, workspaceByteBudget: workspace.length }));
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
