import { spawn } from "node:child_process";
import { once } from "node:events";
import { constants } from "node:os";
import { promisify } from "node:util";
import { WebSocket } from "ws";
import { discoverCapabilities, present } from "./gateway-capabilities.ts";
import { fragments } from "./lines.ts";

/** A WebSocket message as one buffer: ws hands over a buffer, a list of them, or an ArrayBuffer. */
function asBuffer(data: Buffer | ArrayBuffer | Buffer[]): Buffer {
	if (Array.isArray(data)) {
		return Buffer.concat(data);
	}
	return Buffer.isBuffer(data) ? data : Buffer.from(data);
}

class LineOverBudgetError extends Error {
	name = "LineOverBudgetError";
}

/**
 * The runner's newline-terminated lines, refused as soon as one grows past the frame budget — before
 * it is held whole, which is why this reads fragments rather than records. A trailing line the
 * runner never terminated is not a frame and is not forwarded.
 */
async function* boundedLines(source: AsyncIterable<unknown>, maxBytes: number) {
	let pending: Buffer[] = [];
	let pendingBytes = 0;
	for await (const fragment of fragments(source)) {
		pendingBytes += fragment.bytes.length;
		if (pendingBytes > maxBytes) {
			throw new LineOverBudgetError("Line over budget");
		}
		pending.push(fragment.bytes);
		if (!fragment.end) {
			continue;
		}
		yield Buffer.concat(pending).toString("utf8");
		pending = [];
		pendingBytes = 0;
	}
}

async function main() {
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	const [command, ...args] = process.argv.slice(2);
	if (!present(endpoint) || !present(token) || !present(command)) {
		throw new Error("Command and gateway routing are required");
	}
	const headers = { Authorization: `Bearer ${token}` };
	const { frameByteBudget } = await discoverCapabilities(endpoint, headers);
	if (frameByteBudget === null) {
		throw new Error("Interactive gateway capability required");
	}
	const url = new URL(`${endpoint}/frames`);
	url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
	const socket = new WebSocket(url, { headers, maxPayload: frameByteBudget });
	await once(socket, "open");
	const child = spawn(command, args, { stdio: ["pipe", "pipe", "inherit"] });
	socket.on("close", () => {
		child.kill("SIGKILL");
	});
	socket.on("error", () => {
		child.kill("SIGKILL");
	});
	socket.on("message", (data, binary) => {
		if (binary) {
			socket.close(1003);
			return;
		}
		const bytes = asBuffer(data);
		if (!child.stdin.write(Buffer.concat([bytes, Buffer.from("\n")]))) {
			socket.pause();
		}
	});
	child.stdin.on("drain", () => socket.resume());
	child.stdin.on("error", () => socket.close(1011));
	const exited = Promise.withResolvers<number>();
	child.once("error", exited.reject);
	child.once("exit", (code, signal) =>
		exited.resolve(code ?? 128 + (signal === null ? 0 : constants.signals[signal])),
	);
	const disconnected = once(socket, "close");
	const send = promisify(socket.send.bind(socket)) as (line: string) => Promise<void>;
	try {
		const forwarding = (async () => {
			for await (const line of boundedLines(child.stdout, frameByteBudget)) {
				await send(line);
			}
		})();
		let exitCode: number;
		try {
			[exitCode] = await Promise.all([exited.promise, forwarding]);
		} catch (error) {
			if (!(error instanceof LineOverBudgetError)) {
				throw error;
			}
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

try {
	await main();
} catch (error) {
	console.error(error);
	process.exitCode = 1;
}
