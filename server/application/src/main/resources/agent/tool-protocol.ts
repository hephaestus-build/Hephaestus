// tool-protocol.ts — tool protocol v1: how a trusted loop drives an untrusted tools program.
//
// ADR 0068 § Tool protocol v1. The loop dials and sends requests; the tools side only answers. One frame is a 4-byte
// big-endian header length, a JSON header, and an optional body of `header.bodyLength` bytes. Each side treats the
// other's frames as untrusted: it caps every frame and every stream, and validates every header before it acts.
// tool-client.ts is the loop's end, tools-server.ts the tools side.

export const PROTOCOL = 1;

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
	/** The most bytes of one call's spill file; output past it is lost, and the call says so. */
	spillBytes: 256 * 1024 * 1024,
	/** The most bytes of one `put` archive: the checkout bound. */
	putBytes: 2 * 1024 * 1024 * 1024,
	/** The most bytes of a file that `read` pages. */
	readFileBytes: 64 * 1024 * 1024,
	/** The longest time that one `exec` may run. */
	execTimeoutMs: 2 * 60 * 60 * 1000,
} as const;

/** The final frame type of each request; any other frame under its id is a stream frame or a protocol error. */
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

/** Splits a byte stream into frames, refusing any frame past the limits before it buffers its body. */
export function frameReader(): { push: (chunk: Buffer) => Frame[] } {
	let buffered = Buffer.alloc(0);
	return {
		push(chunk) {
			buffered = Buffer.concat([buffered, chunk]);
			const frames: Frame[] = [];
			for (;;) {
				if (buffered.length < 4) {
					return frames;
				}
				const headerLength = buffered.readUInt32BE(0);
				if (headerLength > LIMITS.headerBytes) {
					throw broken("frame header too large");
				}
				if (buffered.length < 4 + headerLength) {
					return frames;
				}
				const header = parseHeader(buffered.subarray(4, 4 + headerLength));
				const { bodyLength } = header;
				if (typeof bodyLength !== "number" || !Number.isInteger(bodyLength) || bodyLength < 0) {
					throw broken("frame without a body length");
				}
				if (bodyLength > LIMITS.bodyBytes) {
					throw broken("frame body too large");
				}
				const end = 4 + headerLength + bodyLength;
				if (buffered.length < end) {
					return frames;
				}
				frames.push({ header, body: Buffer.from(buffered.subarray(4 + headerLength, end)) });
				buffered = buffered.subarray(end);
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
	/** The spill file that holds the whole output. */
	fullOutputPath: string;
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
}
