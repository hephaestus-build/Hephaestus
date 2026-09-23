import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { upload } from "./gateway-run.ts";

for (const scenario of [
	{ name: "retries an unfinished upload", responses: [503, 204] },
	{
		name: "accepts an already committed result",
		responses: [409],
		converged: true,
	},
	{
		name: "rejects a non-owning worker conflict",
		responses: [409],
		expectedError: "Result upload was not confirmed: 409",
	},
	{
		name: "rejects a different admitted result",
		responses: [409],
		expectedError: "Result upload was not confirmed: 409",
		wrongDigest: true,
	},
	{
		name: "rejects an unconfirmed successful upload",
		responses: [204],
		expectedError: "Result upload was not confirmed: 204",
		wrongDigest: true,
	},
	{
		name: "does not retry a refused archive",
		responses: [422],
		expectedError: "Result upload refused: 422",
	},
	{ name: "retries a disconnected response", responses: [null, 204] },
]) {
	void test(scenario.name, async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "gateway-upload-"));
		const requests: {
			method: string | undefined;
			authorization: string | undefined;
			contentType: string | undefined;
			contentDigest: string | string[] | undefined;
			length: string | undefined;
			body: Buffer;
		}[] = [];
		const server = createServer((request, response) => {
			const chunks: Buffer[] = [];
			request.on("data", (chunk: Buffer) => {
				chunks.push(chunk);
			});
			request.on("end", () => {
				const status = scenario.responses[requests.length];
				requests.push({
					method: request.method,
					authorization: request.headers.authorization,
					contentType: request.headers["content-type"],
					contentDigest: request.headers["content-digest"],
					length: request.headers["content-length"],
					body: Buffer.concat(chunks),
				});
				if (status === null) {
					request.socket.destroy();
				} else {
					response.statusCode = status ?? 500;
					if (
						status === 204 ||
						(status === 409 && ("converged" in scenario || "wrongDigest" in scenario))
					) {
						response.setHeader(
							"ETag",
							`"${createHash("sha256")
								.update("wrongDigest" in scenario ? "other" : Buffer.concat(chunks))
								.digest("hex")}"`,
						);
					}
					response.end();
				}
			});
		});
		try {
			await writeFile(path.join(directory, "observations.json"), "{}");
			const archive = path.join(directory, "result.tar");
			execFileSync("tar", ["-cf", archive, "-C", directory, "observations.json"]);
			const bytes = await readFile(archive);
			server.listen(0, "127.0.0.1");
			await once(server, "listening");
			const address = server.address();
			assert.ok(address !== null && typeof address !== "string");
			const endpoint = new URL(`http://127.0.0.1:${address.port}/result`);
			context.diagnostic(endpoint.href);
			const uploaded = upload(endpoint, "test-credential", archive);
			if ("expectedError" in scenario) {
				await assert.rejects(uploaded, { message: scenario.expectedError });
			} else {
				await uploaded;
			}
			assert.equal(requests.length, scenario.responses.length);
			for (const request of requests) {
				assert.equal(request.method, "POST");
				assert.equal(request.authorization, "Bearer test-credential");
				assert.equal(request.contentType, "application/x-tar");
				assert.equal(
					request.contentDigest,
					`sha-256=:${createHash("sha256").update(bytes).digest("base64")}:`,
				);
				assert.equal(request.length, String(bytes.length));
				assert.deepEqual(request.body, bytes);
			}
		} finally {
			const closed = once(server, "close");
			server.close();
			server.closeAllConnections();
			await closed;
			await rm(directory, { recursive: true, force: true });
		}
	});
}
