import { spawn } from "node:child_process";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { discoverCapabilities } from "./gateway-capabilities.ts";

async function main() {
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!endpoint || !token) throw new Error("Gateway credential and runtime endpoint are required");
	const headers = { Authorization: `Bearer ${token}` };
	const budget = (await discoverCapabilities(endpoint, headers)).workspaceByteBudget;
	const response = await fetch(`${endpoint}/workspace`, { headers });
	if (!response.ok || !response.body)
		throw new Error(`Workspace download refused: ${response.status}`);
	let bytes = 0;
	const bound = new Transform({
		transform(chunk: Buffer, _encoding, callback) {
			bytes += chunk.length;
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
	const extracted = new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("exit", (code) =>
			code === 0 ? resolve() : reject(new Error(`Workspace extraction failed: ${code}`)),
		);
	});
	try {
		await Promise.all([pipeline(response.body, bound, child.stdin), extracted]);
		if (bytes !== budget) throw new Error("Incomplete workspace download");
	} catch (error) {
		child.kill("SIGKILL");
		throw error;
	}
}

main().catch((error: unknown) => {
	console.error(error);
	process.exitCode = 1;
});
