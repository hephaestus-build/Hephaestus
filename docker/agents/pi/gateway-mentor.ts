import { spawn } from "node:child_process";
import { once } from "node:events";
import { createInterface } from "node:readline";
import { WebSocket } from "ws";

async function main() {
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	const [command, ...args] = process.argv.slice(2);
	if (!endpoint || !token || !command) throw new Error("Command and gateway routing are required");
	const headers = { Authorization: `Bearer ${token}` };
	const discovery = await fetch(endpoint, { headers });
	if (!discovery.ok) throw new Error(`Gateway discovery refused: ${discovery.status}`);
	const capabilities: unknown = await discovery.json();
	if (
		typeof capabilities !== "object" ||
		capabilities === null ||
		!("frameByteBudget" in capabilities) ||
		typeof capabilities.frameByteBudget !== "number" ||
		!Number.isSafeInteger(capabilities.frameByteBudget) ||
		capabilities.frameByteBudget <= 0
	)
		throw new Error("Interactive gateway capability required");
	const url = new URL(`${endpoint}/frames`);
	url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
	const socket = new WebSocket(url, { headers, maxPayload: capabilities.frameByteBudget });
	await once(socket, "open");
	const child = spawn(command, args, { stdio: ["pipe", "pipe", "inherit"] });
	socket.on("close", () => child.kill("SIGKILL"));
	socket.on("error", () => child.kill("SIGKILL"));
	socket.on("message", (data, binary) => {
		if (binary) {
			socket.close(1003);
			return;
		}
		const bytes = Array.isArray(data)
			? Buffer.concat(data)
			: Buffer.isBuffer(data)
				? data
				: Buffer.from(data);
		if (!child.stdin.write(Buffer.concat([bytes, Buffer.from("\n")]))) socket.pause();
	});
	child.stdin.on("drain", () => socket.resume());
	child.stdin.on("error", () => socket.close(1011));
	const exited = new Promise<number>((resolve, reject) => {
		child.once("error", reject);
		child.once("exit", (code) => resolve(code ?? 1));
	});
	const disconnected = new Promise<void>((resolve) => {
		socket.once("close", () => resolve());
	});
	try {
		const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
		const forwarding = (async () => {
			for await (const line of lines) {
				await new Promise<void>((resolve, reject) => {
					socket.send(line, (error) => (error ? reject(error) : resolve()));
				});
			}
		})();
		const [exitCode] = await Promise.all([exited, forwarding]);
		socket.close(4000 + exitCode);
		await disconnected;
		process.exitCode = exitCode;
	} finally {
		socket.terminate();
		child.kill("SIGKILL");
	}
}

main().catch((error: unknown) => {
	console.error(error);
	process.exitCode = 1;
});
