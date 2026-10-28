// tool-client.ts — the loop's end of tool protocol v1 (tool-protocol.ts).

import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import { connect, type Socket } from "node:net";

import {
	encode,
	type ExecExit,
	type ExecRequest,
	FINAL,
	type Frame,
	frameReader,
	type Header,
	integer,
	LIMITS,
	PROTOCOL,
	type ReadPage,
	text,
	ToolProtocolError,
} from "./tool-protocol.ts";

interface Pending {
	/** The type of the frame that ends the request. */
	final: string;
	/** The type of the request's stream frames, if it streams. */
	stream?: string;
	onStream: (frame: Frame) => void;
	done: PromiseWithResolvers<Frame>;
}

function broken(message: string): ToolProtocolError {
	return new ToolProtocolError("protocol", message);
}

/**
 * One connection to the tools side, which the loop treats as untrusted. Every request names an id, and the tools side
 * answers under it with the request's stream frames and then its one final frame. Any other frame, or silence past
 * {@link LIMITS.silenceMs}, ends the connection.
 */
export class ToolClient {
	/** The tools side's boot id: a changed one on a new connection means that the tools side restarted. */
	bootId = "";

	readonly #socket: Socket;
	readonly #pending = new Map<string, Pending>();
	readonly #reader = frameReader();
	readonly #keepalive: NodeJS.Timeout;
	#lastFrameAt = Date.now();
	#streamedBytes = 0;
	#closed: Error | undefined;

	private constructor(socket: Socket) {
		this.#socket = socket;
		socket.on("data", (chunk: Buffer) => {
			this.#receive(chunk);
		});
		socket.on("error", (error) => {
			this.#close(error);
		});
		socket.on("close", () => {
			this.#close(broken("the tools side closed the connection"));
		});
		this.#keepalive = setInterval(() => {
			if (Date.now() - this.#lastFrameAt > LIMITS.silenceMs) {
				this.#close(broken("the tools side stopped answering"));
				return;
			}
			this.#write({ type: "ping" });
		}, LIMITS.keepaliveMs);
		this.#keepalive.unref();
	}

	/** Connects and says hello, within {@link LIMITS.silenceMs}. */
	static async open(socketPath: string): Promise<ToolClient> {
		const signal = AbortSignal.timeout(LIMITS.silenceMs);
		const socket = connect(socketPath);
		await once(socket, "connect", { signal });
		const client = new ToolClient(socket);
		const nonce = randomUUID();
		signal.addEventListener("abort", () => {
			client.#close(broken("the tools side did not answer hello"));
		});
		const reply = await client.#request(
			"hello",
			{ type: "hello", nonce, protocol: PROTOCOL },
			FINAL.hello,
		);
		const bootId = text(reply.header, "bootId");
		if (
			text(reply.header, "nonce") !== nonce ||
			integer(reply.header, "protocol") !== PROTOCOL ||
			bootId === undefined ||
			bootId === ""
		) {
			client.close();
			throw broken("the tools side answered hello with another nonce or protocol");
		}
		client.bootId = bootId;
		return client;
	}

	/** Runs one program; `onOutput` receives its output until the stream bounds stop it. */
	async exec(
		request: ExecRequest,
		onOutput: (chunk: Buffer) => void,
		signal?: AbortSignal,
	): Promise<ExecExit> {
		const id = randomUUID();
		let streamed = 0;
		const cancel = () => {
			this.#write({ type: "cancel", id });
		};
		signal?.addEventListener("abort", cancel, { once: true });
		try {
			const exit = this.#request(
				id,
				{ ...request, type: "exec", id },
				FINAL.exec,
				"out",
				(frame) => {
					streamed += frame.body.length;
					this.#streamedBytes += frame.body.length;
					if (
						streamed > LIMITS.execStreamBytes ||
						this.#streamedBytes > LIMITS.attemptStreamBytes
					) {
						throw broken("the tools side streamed more than its bound");
					}
					onOutput(frame.body);
				},
			);
			if (signal?.aborted === true) {
				cancel();
			}
			const { header } = await exit;
			return {
				code: integer(header, "code") ?? null,
				signal: text(header, "signal") ?? null,
				timedOut: header.timedOut === true,
				truncated: header.truncated === true,
				fullOutputPath: text(header, "fullOutputPath") ?? "",
			};
		} finally {
			signal?.removeEventListener("abort", cancel);
		}
	}

	async read(path: string, offset: number, limit: number): Promise<ReadPage> {
		const id = randomUUID();
		const reply = await this.#request(id, { type: "read", id, path, offset, limit }, FINAL.read);
		const first = integer(reply.header, "offset");
		const totalLines = integer(reply.header, "totalLines");
		if (first === undefined || totalLines === undefined) {
			throw broken("malformed read reply");
		}
		return { text: reply.body.toString("utf8"), offset: first, totalLines };
	}

	/** Writes a tar archive into a new directory `dir` on the tools side, which extracts it read-only. */
	async put(dir: string, archive: Buffer): Promise<void> {
		if (archive.length > LIMITS.putBytes) {
			throw new ToolProtocolError("request", "The archive is larger than a checkout may be.");
		}
		const id = randomUUID();
		const sha256 = createHash("sha256").update(archive).digest("hex");
		// Settled at once, so that a refusal that arrives while the archive streams is never unhandled.
		const reply = Promise.allSettled([
			this.#request(id, { type: "put", id, dir, size: archive.length, sha256 }, FINAL.put),
		]);
		for (let at = 0; at < archive.length && !this.#closed; at += LIMITS.bodyBytes) {
			if (!this.#write({ type: "put-data", id }, archive.subarray(at, at + LIMITS.bodyBytes))) {
				await once(this.#socket, "drain");
			}
		}
		this.#write({ type: "put-end", id });
		const [outcome] = await reply;
		if (outcome.status === "rejected") {
			throw outcome.reason;
		}
	}

	/** Ends every process of the tools user and clears its scratch. */
	async reset(): Promise<void> {
		const id = randomUUID();
		await this.#request(id, { type: "reset", id }, FINAL.reset);
	}

	close(): void {
		this.#close(broken("the connection is closed"));
	}

	/** Sends one request and resolves with its final frame, handing its stream frames to `onStream`. */
	async #request(
		id: string,
		header: Header,
		final: string,
		stream?: string,
		onStream: (frame: Frame) => void = () => undefined,
	): Promise<Frame> {
		if (this.#closed) {
			throw this.#closed;
		}
		if (this.#pending.size >= LIMITS.inFlight) {
			throw new ToolProtocolError("busy", "Too many tool calls at once.");
		}
		const done = Promise.withResolvers<Frame>();
		this.#pending.set(id, { final, stream, onStream, done });
		this.#write(header);
		try {
			return await done.promise;
		} finally {
			this.#pending.delete(id);
		}
	}

	#receive(chunk: Buffer): void {
		try {
			for (const frame of this.#reader.push(chunk)) {
				this.#lastFrameAt = Date.now();
				this.#dispatch(frame);
			}
		} catch (error) {
			this.#close(error instanceof Error ? error : broken(String(error)));
		}
	}

	#dispatch(frame: Frame): void {
		const { type } = frame.header;
		if (type === "pong") {
			return;
		}
		const id = type === "hello" ? "hello" : text(frame.header, "id");
		const pending = id === undefined ? undefined : this.#pending.get(id);
		if (pending === undefined) {
			throw broken(`a frame for no request in flight: ${type}`);
		}
		if (type === pending.stream) {
			pending.onStream(frame);
		} else if (type === "error") {
			this.#pending.delete(id ?? "");
			pending.done.reject(
				new ToolProtocolError(
					text(frame.header, "error") ?? "error",
					text(frame.header, "detail") ?? "",
				),
			);
		} else if (type === pending.final) {
			this.#pending.delete(id ?? "");
			pending.done.resolve(frame);
		} else {
			throw broken(`a ${type} frame answers a request that ends with ${pending.final}`);
		}
	}

	/** Writes one frame; `false` when the socket's buffer is full. */
	#write(header: Header, body?: Buffer): boolean {
		if (this.#closed) {
			return true;
		}
		return this.#socket.write(encode(header, body));
	}

	#close(error: Error): void {
		if (this.#closed) {
			return;
		}
		this.#closed = error;
		clearInterval(this.#keepalive);
		this.#socket.destroy();
		for (const pending of this.#pending.values()) {
			pending.done.reject(error);
		}
		this.#pending.clear();
	}
}
