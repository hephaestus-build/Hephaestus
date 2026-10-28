// tool-client.ts — the loop's end of tool protocol v1 (tool-protocol.ts).

import { randomUUID } from "node:crypto";
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

/** A `put` archive: the bytes of a tar file, with the size and SHA-256 that the tools side checks before it extracts. */
export interface PutArchive {
	bytes: AsyncIterable<Buffer>;
	size: number;
	sha256: string;
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
	/** Aborted when the connection ends, so that nothing waits on it any more. */
	readonly #ending = new AbortController();
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

	/**
	 * Connects and says hello, within {@link LIMITS.silenceMs}. `nonce` is the one that the worker wrote into the tools
	 * Pod's spec: the loop never sends it, so only that Pod's tools side can answer with it.
	 */
	static async open(socketPath: string, nonce: string): Promise<ToolClient> {
		const client = new ToolClient(connect(socketPath));
		const timer = setTimeout(() => {
			client.#close(broken("the tools side did not answer hello"));
		}, LIMITS.silenceMs);
		try {
			const reply = await client.#request(
				"hello",
				{ type: "hello", protocol: PROTOCOL },
				FINAL.hello,
			).reply;
			if (integer(reply.header, "protocol") !== PROTOCOL) {
				throw new ToolProtocolError("version", "the tools side speaks another protocol version");
			}
			if (text(reply.header, "nonce") !== nonce) {
				throw new ToolProtocolError(
					"nonce",
					"the tools side is not the Pod that the worker started",
				);
			}
			const bootId = text(reply.header, "bootId");
			if (bootId === undefined || bootId === "") {
				throw broken("the tools side answered hello without a boot id");
			}
			client.bootId = bootId;
			return client;
		} catch (error) {
			client.close();
			throw error;
		} finally {
			clearTimeout(timer);
		}
	}

	/**
	 * Runs one program; `onOutput` receives its output until the stream bounds stop it. If `onOutput` throws, the call
	 * is cancelled and rejects with that error, and the connection's other calls go on.
	 */
	async exec(
		request: ExecRequest,
		onOutput: (chunk: Buffer) => void,
		signal?: AbortSignal,
	): Promise<ExecExit> {
		const id = randomUUID();
		const bound = Math.min(
			request.maxOutputBytes ?? LIMITS.execStreamBytes,
			LIMITS.execStreamBytes,
		);
		let streamed = 0;
		let outputFailed: { error: unknown } | undefined;
		let grace: NodeJS.Timeout | undefined;
		const cancel = () => {
			if (grace !== undefined || !this.#pending.has(id)) {
				return;
			}
			this.#write({ type: "cancel", id });
			grace = setTimeout(() => {
				this.#close(broken("the tools side did not end a cancelled call"));
			}, LIMITS.cancelGraceMs);
			grace.unref();
		};
		const { reply: exit } = this.#request(
			id,
			{ ...request, type: "exec", id },
			FINAL.exec,
			"out",
			(frame) => {
				streamed += frame.body.length;
				this.#streamedBytes += frame.body.length;
				if (streamed > bound || this.#streamedBytes > LIMITS.attemptStreamBytes) {
					throw broken("the tools side streamed more than its bound");
				}
				if (outputFailed !== undefined) {
					return;
				}
				try {
					onOutput(frame.body);
				} catch (error) {
					outputFailed = { error };
					cancel();
				}
			},
		);
		signal?.addEventListener("abort", cancel, { once: true });
		try {
			if (signal?.aborted === true) {
				cancel();
			}
			const { header } = await exit;
			if (outputFailed !== undefined) {
				throw outputFailed.error;
			}
			return {
				code: integer(header, "code") ?? null,
				signal: text(header, "signal") ?? null,
				timedOut: header.timedOut === true,
				truncated: header.truncated === true,
				fullOutputPath: text(header, "fullOutputPath") ?? "",
				outputLost: header.outputLost === true,
			};
		} finally {
			clearTimeout(grace);
			signal?.removeEventListener("abort", cancel);
		}
	}

	async read(path: string, offset: number, limit: number): Promise<ReadPage> {
		const id = randomUUID();
		const reply = await this.#request(id, { type: "read", id, path, offset, limit }, FINAL.read)
			.reply;
		const first = integer(reply.header, "offset");
		const totalLines = integer(reply.header, "totalLines");
		const nextOffset = integer(reply.header, "nextOffset");
		if (first === undefined || totalLines === undefined || nextOffset === undefined) {
			throw broken("malformed read reply");
		}
		return {
			text: reply.body.toString("utf8"),
			offset: first,
			totalLines,
			truncated: reply.header.truncated === true,
			nextOffset,
		};
	}

	/**
	 * Streams a tar archive into a new directory `dir` on the tools side, which extracts it read-only once its size and
	 * SHA-256 match. It waits while the socket is full, so the loop holds at most one frame of the archive at a time, and
	 * it stops as soon as the tools side refuses the put.
	 */
	async put(dir: string, archive: PutArchive): Promise<void> {
		if (archive.size > LIMITS.putBytes) {
			throw new ToolProtocolError("request", "The archive is larger than a checkout may be.");
		}
		const id = randomUUID();
		// #request reserves the call's slot before it returns, or throws at once; settled at once, so that a refusal
		// that arrives while the archive streams is never unhandled.
		const reply = Promise.allSettled([
			this.#request(
				id,
				{ type: "put", id, dir, size: archive.size, sha256: archive.sha256 },
				FINAL.put,
			).reply,
		]);
		let room = archive.size;
		let sourceFailure: { error: unknown } | undefined;
		try {
			for await (const chunk of archive.bytes) {
				room = await this.#putChunk(id, chunk, room);
				if (room <= 0) {
					break;
				}
			}
		} catch (error) {
			sourceFailure = { error };
		}
		// Also acknowledges a remote refusal: its already-sent data may still be arriving. An aborted source never
		// extracts, even when it failed after supplying all the announced bytes. The final reply proves cleanup.
		this.#write({ type: "put-end", id, abort: sourceFailure !== undefined });
		const [outcome] = await reply;
		if (sourceFailure !== undefined) {
			throw sourceFailure.error;
		}
		if (outcome.status === "rejected") {
			throw outcome.reason;
		}
	}

	/** Sends one chunk of a put's archive and returns the bytes that the put still takes: 0 once it stops. */
	async #putChunk(id: string, chunk: Buffer, room: number): Promise<number> {
		let left = room;
		for (let at = 0; at < chunk.length && left > 0; at += LIMITS.bodyBytes) {
			// The put is no longer pending once the tools side refused it or the connection ended.
			if (!this.#pending.has(id)) {
				return 0;
			}
			const part = chunk.subarray(at, at + Math.min(LIMITS.bodyBytes, left));
			left -= part.length;
			if (!this.#write({ type: "put-data", id }, part)) {
				try {
					await once(this.#socket, "drain", { signal: this.#ending.signal });
				} catch {
					return 0;
				}
			}
		}
		return left;
	}

	/** Ends every process of the tools user and clears its scratch. */
	async reset(): Promise<void> {
		const id = randomUUID();
		await this.#request(id, { type: "reset", id }, FINAL.reset).reply;
	}

	close(): void {
		this.#close(broken("the connection is closed"));
	}

	/**
	 * Sends one request, whose `reply` resolves with its final frame; its stream frames go to `onStream`. It holds the
	 * request's slot before it returns, and throws at once when the connection ended or every slot is taken.
	 */
	#request(
		id: string,
		header: Header,
		final: string,
		stream?: string,
		onStream: (frame: Frame) => void = () => undefined,
	): { reply: Promise<Frame> } {
		if (this.#closed) {
			throw this.#closed;
		}
		if (this.#pending.size >= LIMITS.inFlight) {
			throw new ToolProtocolError("busy", "Too many tool calls at once.");
		}
		const done = Promise.withResolvers<Frame>();
		this.#pending.set(id, { final, stream, onStream, done });
		this.#write(header);
		return { reply: done.promise };
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
		if (id === undefined || pending === undefined) {
			throw broken(`a frame for no request in flight: ${type}`);
		}
		if (type === pending.stream) {
			pending.onStream(frame);
		} else if (type === "error") {
			this.#pending.delete(id);
			pending.done.reject(
				new ToolProtocolError(
					text(frame.header, "error") ?? "error",
					text(frame.header, "detail") ?? "",
				),
			);
		} else if (type === pending.final) {
			this.#pending.delete(id);
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
		this.#ending.abort();
		this.#socket.destroy();
		for (const pending of this.#pending.values()) {
			pending.done.reject(error);
		}
		this.#pending.clear();
	}
}
