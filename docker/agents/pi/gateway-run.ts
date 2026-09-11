import { spawn } from "node:child_process";
import { createReadStream } from "node:fs";
import { mkdtemp, rm, stat } from "node:fs/promises";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { tmpdir } from "node:os";
import { join } from "node:path";

function run(command: string, args: string[]) {
	const child = spawn(command, args, { stdio: "inherit" });
	return new Promise<number>((resolve, reject) => {
		child.once("error", reject);
		child.once("exit", (code) => resolve(code ?? 1));
	});
}

async function main() {
	const [command, ...args] = process.argv.slice(2);
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!command || !endpoint || !token) throw new Error("Command and gateway routing are required");
	const exitCode = await run(command, args);
	const directory = await mkdtemp(join(tmpdir(), "gateway-output-"));
	try {
		const archive = join(directory, "result.tar");
		if (
			(await run("tar", [
				"--create",
				"--format=ustar",
				"--blocking-factor=1",
				"--file",
				archive,
				"--directory",
				"/workspace",
				"out",
			])) !== 0
		)
			throw new Error("Could not package sandbox result");
		const size = (await stat(archive)).size;
		await new Promise<void>((resolve, reject) => {
			const url = new URL(`${endpoint}/result`);
			const request = url.protocol === "https:" ? httpsRequest : httpRequest;
			const upload = request(
				url,
				{
					method: "POST",
					headers: {
						Authorization: `Bearer ${token}`,
						"Content-Type": "application/x-tar",
						"Content-Length": size,
					},
				},
				(response) => {
					response.resume();
					response.once("end", () =>
						response.statusCode === 204 || response.statusCode === 409
							? resolve()
							: reject(new Error(`Result upload refused: ${response.statusCode}`)),
					);
				},
			);
			upload.once("error", reject);
			const input = createReadStream(archive);
			input.once("error", (error) => {
				upload.destroy(error);
				reject(error);
			});
			input.pipe(upload);
		});
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
	process.exitCode = exitCode;
}

main().catch((error: unknown) => {
	console.error(error);
	process.exitCode = 1;
});
