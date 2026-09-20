import { spawn } from "node:child_process";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { discoverCapabilities, present } from "./gateway-capabilities.ts";

async function main() {
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!present(endpoint) || !present(token)) {
		throw new Error("Gateway credential and runtime endpoint are required");
	}
	const headers = { Authorization: `Bearer ${token}` };
	const capabilities = await discoverCapabilities(endpoint, headers);
	const budget = capabilities.workspaceByteBudget;
	const response = await fetch(`${endpoint}/workspace`, { headers });
	if (!response.ok || !response.body) {
		throw new Error(`Workspace download refused: ${response.status}`);
	}
	let bytes = 0;
	const bound = new Transform({
		// The stream contract is a callback; a Transform has no promise form.
		// oxlint-disable-next-line promise/prefer-await-to-callbacks -- Node's Transform API takes the callback.
		transform(chunk: Buffer, _encoding, callback) {
			bytes += chunk.length;
			// oxlint-disable-next-line promise/prefer-await-to-callbacks -- The same callback, answered.
			callback(
				bytes > budget ? new Error("Workspace exceeded its advertised byte budget") : null,
				chunk,
			);
		},
	});
	const child = spawn(
		"tar",
		[
			"--extract",
			"--file",
			"-",
			"--directory",
			"/workspace",
			"--ignore-zeros",
			"--no-same-owner",
			"--no-same-permissions",
			"--delay-directory-restore",
		],
		{ stdio: ["pipe", "inherit", "inherit"] },
	);
	const extracted = Promise.withResolvers<undefined>();
	child.once("error", extracted.reject);
	child.once("exit", (code) => {
		if (code === 0) {
			extracted.resolve(undefined);
		} else {
			extracted.reject(new Error(`Workspace extraction failed: ${code}`));
		}
	});
	try {
		await Promise.all([pipeline(response.body, bound, child.stdin), extracted.promise]);
		if (bytes !== budget) {
			throw new Error("Incomplete workspace download");
		}
	} catch (error) {
		child.kill("SIGKILL");
		throw error;
	}
}

try {
	await main();
} catch (error) {
	console.error(error);
	process.exitCode = 1;
}
