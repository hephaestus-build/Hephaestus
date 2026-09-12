import { spawn } from "node:child_process";
import { once } from "node:events";
import { constants } from "node:os";
import { WebSocket } from "ws";
import { discoverCapabilities } from "./gateway-capabilities.ts";

class LineOverBudget extends Error {}

/** The runner's newline-terminated lines, refused as soon as one grows past the frame budget. */
async function* boundedLines(source: AsyncIterable<unknown>, maxBytes: number) {
	let pending: Buffer[] = [];
	let pendingBytes = 0;
	for await (const chunk of source) {
		if (!Buffer.isBuffer(chunk)) throw new Error("Runner output must be bytes");
		let start = 0;
		let end: number;
		while ((end = chunk.indexOf(10, start)) !== -1) {
			if (pendingBytes + end - start > maxBytes) throw new LineOverBudget("Line over budget");
			yield Buffer.concat([...pending, chunk.subarray(start, end)]).toString("utf8");
			pending = [];
			pendingBytes = 0;
			start = end + 1;
		}
		if (start < chunk.length) {
			pendingBytes += chunk.length - start;
			if (pendingBytes > maxBytes) throw new LineOverBudget("Line over budget");
			pending.push(chunk.subarray(start));
		}
	}
}

async function main() {
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	const [command, ...args] = process.argv.slice(2);
	if (!endpoint || !token || !command) throw new Error("Command and gateway routing are required");
	const headers = { Authorization: `Bearer ${token}` };
	const { frameByteBudget } = await discoverCapabilities(endpoint, headers);
	if (frameByteBudget === null) throw new Error("Interactive gateway capability required");
	const url = new URL(`${endpoint}/frames`);
	url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
	const socket = new WebSocket(url, { headers, maxPayload: frameByteBudget });
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
		child.once("exit", (code, signal) =>
			resolve(code ?? 128 + (signal === null ? 0 : constants.signals[signal])),
		);
	});
	const disconnected = new Promise<void>((resolve) => {
		socket.once("close", () => resolve());
	});
	try {
		const forwarding = (async () => {
			for await (const line of boundedLines(child.stdout, frameByteBudget)) {
				await new Promise<void>((resolve, reject) => {
					socket.send(line, (error) => (error ? reject(error) : resolve()));
				});
			}
		})();
		let exitCode: number;
		try {
			[exitCode] = await Promise.all([exited, forwarding]);
		} catch (error) {
			if (!(error instanceof LineOverBudget)) throw error;
			console.error(`runner wrote a line over the ${frameByteBudget}-byte frame budget; closing`);
			socket.close(1009, "line over frame budget");
			await disconnected;
			process.exitCode = 1;
			return;
		}
		// The server reads the runner's exit status from this range of close codes.
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
