// tool-protocol.ts — tool protocol v1: how a trusted loop drives an untrusted tools program.
//
// ADR 0068 § Tool protocol v1. The loop dials and sends requests; the tools side only answers. One frame is a 4-byte
// big-endian header length, a JSON header, and an optional body of `header.bodyLength` bytes. Each side treats the
// other's frames as untrusted: it caps every frame and every stream, and validates every header before it acts.
// tool-client.ts is the loop's end, tools-server.ts the tools side.

export const PROTOCOL = 1;

/** The two real transports: a review sidecar's Unix socket, or a conversation tools Job's TCP port. */
export type ToolEndpoint = string | { host: string; port: number };

/** Mutable tools data has a private parent owned by the command UID, so reset can replace it atomically. */
export const TOOL_WORKSPACE = "/tmp/tools/workspace";
export const TOOL_CONTEXT_ROOT = `${TOOL_WORKSPACE}/context`;
export const TOOL_SCRATCH_ROOT = `${TOOL_WORKSPACE}/.scratch`;

export const LIMITS = {
	/** The most bytes of one JSON header. */
	headerBytes: 64 * 1024,
	/** The most bytes of one frame's body. */
	bodyBytes: 1024 * 1024,
	/** The most requests in flight on one connection: Pi runs parallel tool calls at once. */
	inFlight: 8,
	/** The most output bytes streamed for one `exec`; the rest goes only to its spill file. */
	execStreamBytes: 8 * 1024 * 1024,
	/** The most output bytes streamed for one attempt, across all its calls. */
	attemptStreamBytes: 64 * 1024 * 1024,
	/** How often the loop proves the connection alive. */
	keepaliveMs: 10_000,
	/** How long the loop waits for any frame before it ends the connection. */
	silenceMs: 30_000,
	/** The most bytes of one call's spill file; output past it is lost, and the call's `outputLost` says so. */
	spillBytes: 256 * 1024 * 1024,
	/** The most bytes of one `put` archive: the checkout bound. */
	putBytes: 2 * 1024 * 1024 * 1024,
	/** The most bytes of a file that `read` pages. */
	readFileBytes: 64 * 1024 * 1024,
	/** The longest time that one `exec` may run. */
	execTimeoutMs: 2 * 60 * 60 * 1000,
	/** How long the loop waits for the `exit` of a cancelled call before it ends the connection. */
	cancelGraceMs: 10_000,
	/** The longest time that the tools side extracts one `put` archive. */
	extractMs: 10 * 60 * 1000,
} as const;

/**
 * The final frame type of each request; any other frame under its id is a stream frame or a protocol error. `error`
 * ends any request too. The loop ends every upload with `put-end`, also after a refusal. `put-end` with `abort: true`
 * discards the archive without extraction. A put's final reply follows cleanup. `put-data` and `put-end` get no
 * reply of their own, and `cancel` gets none: the call it ends still sends its `exit`.
 */
export const FINAL = {
	hello: "hello",
	exec: "exit",
	read: "read",
	put: "put",
	reset: "reset",
} as const;

/** A request id: it names the spill file, so it carries no path characters. */
export const ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/u;

export type Header = Record<string, unknown> & { type: string };

export interface Frame {
	header: Header;
	body: Buffer;
}

/**
 * A failure of the protocol (`code` "protocol"), which ends the connection, or a refusal of one request, whose message
 * is worded for the model.
 */
export class ToolProtocolError extends Error {
	readonly code: string;

	constructor(code: string, message: string) {
		super(message);
		this.name = "ToolProtocolError";
		this.code = code;
	}
}

function broken(message: string): ToolProtocolError {
	return new ToolProtocolError("protocol", message);
}

export function encode(header: Header, body: Buffer = Buffer.alloc(0)): Buffer {
	if (body.length > LIMITS.bodyBytes) {
		throw broken("frame body too large");
	}
	const json = Buffer.from(JSON.stringify({ ...header, bodyLength: body.length }), "utf8");
	if (json.length > LIMITS.headerBytes) {
		throw broken("frame header too large");
	}
	const length = Buffer.alloc(4);
	length.writeUInt32BE(json.length);
	return Buffer.concat([length, json, body]);
}

function isObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseHeader(bytes: Buffer): Header {
	let value: unknown;
	try {
		value = JSON.parse(bytes.toString("utf8"));
	} catch {
		throw broken("frame header is not JSON");
	}
	if (!isObject(value)) {
		throw broken("frame header is not an object");
	}
	const { type } = value;
	if (typeof type !== "string") {
		throw broken("frame header without a type");
	}
	return { ...value, type };
}

/**
 * Splits a byte stream into frames, refusing any frame past the limits before it buffers its body. It keeps the
 * chunks as they arrive and joins them once a frame is whole, so a large body is not copied on every chunk.
 */
export function frameReader(): { push: (chunk: Buffer) => Frame[] } {
	const chunks: Buffer[] = [];
	let size = 0;
	/** The first `bytes` buffered bytes, which must be buffered, as one buffer. */
	const head = (bytes: number): Buffer => {
		if (chunks[0] === undefined || chunks[0].length < bytes) {
			const joined = Buffer.concat(chunks);
			chunks.length = 0;
			chunks.push(joined);
			return joined.subarray(0, bytes);
		}
		return chunks[0].subarray(0, bytes);
	};
	return {
		push(chunk) {
			chunks.push(chunk);
			size += chunk.length;
			const frames: Frame[] = [];
			for (;;) {
				if (size < 4) {
					return frames;
				}
				const headerLength = head(4).readUInt32BE(0);
				if (headerLength > LIMITS.headerBytes) {
					throw broken("frame header too large");
				}
				if (size < 4 + headerLength) {
					return frames;
				}
				const header = parseHeader(head(4 + headerLength).subarray(4));
				const { bodyLength } = header;
				if (typeof bodyLength !== "number" || !Number.isInteger(bodyLength) || bodyLength < 0) {
					throw broken("frame without a body length");
				}
				if (bodyLength > LIMITS.bodyBytes) {
					throw broken("frame body too large");
				}
				const end = 4 + headerLength + bodyLength;
				if (size < end) {
					return frames;
				}
				const frame = head(end);
				frames.push({ header, body: frame.subarray(4 + headerLength) });
				// head(end) left the whole frame in the first chunk.
				const [first = Buffer.alloc(0)] = chunks;
				if (first.length === end) {
					chunks.shift();
				} else {
					chunks[0] = first.subarray(end);
				}
				size -= end;
			}
		},
	};
}

export function text(header: Header, key: string): string | undefined {
	const value = header[key];
	return typeof value === "string" ? value : undefined;
}

export function integer(header: Header, key: string): number | undefined {
	const value = header[key];
	return typeof value === "number" && Number.isSafeInteger(value) ? value : undefined;
}

export function strings(header: Header, key: string): string[] | undefined {
	const value = header[key];
	if (!Array.isArray(value)) {
		return undefined;
	}
	const parts: string[] = [];
	for (const part of value) {
		if (typeof part !== "string") {
			return undefined;
		}
		parts.push(part);
	}
	return parts;
}

/** How an `exec` ended. */
export interface ExecExit {
	/** `null` when a signal ended the program. */
	code: number | null;
	signal: string | null;
	timedOut: boolean;
	/** Whether output past the stream bounds went only to the spill file. */
	truncated: boolean;
	/** The spill file of the output, or "" when the tools side could not write one. */
	fullOutputPath: string;
	/** Whether some output is in neither the stream nor the spill file: past the spill bound, or with no spill file. */
	outputLost: boolean;
}

export type ExecRequest = ({ command: string } | { argv: string[] }) & {
	cwd: string;
	timeoutMs: number;
	maxOutputBytes?: number;
};

/** A page of lines of one file. */
export interface ReadPage {
	text: string;
	/** The first line of the page, counted from 1. */
	offset: number;
	totalLines: number;
	/**
	 * Whether the body bound ended the page before the lines that were asked for: the page then holds the whole lines
	 * that fit, or only the start of its first line when that line alone is past the bound.
	 */
	truncated: boolean;
	/** The first line after the page, where the next page starts. */
	nextOffset: number;
}
