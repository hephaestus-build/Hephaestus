// The transport that a tool protocol suite runs on. A suite that uses these helpers runs on TCP from a one-line
// `*-tcp.spec.ts` that sets TOOL_PROTOCOL_TRANSPORT and imports it, as tool-protocol-tcp.spec.ts does.

import type { Server } from "node:net";

import type { ToolAddress } from "../../../main/resources/agent/tool-protocol.ts";

export const transport = process.env.TOOL_PROTOCOL_TRANSPORT === "tcp" ? "tcp" : "unix";

/** Where a server of this run listens: `socketPath`, or a free port of the loopback address. */
export function listenAddress(socketPath: string): ToolAddress {
	return transport === "tcp" ? { host: "127.0.0.1", port: 0 } : { path: socketPath };
}

/** Where a client reaches `listening`, which listens on `requested`. */
export function dialAddress(listening: Server, requested: ToolAddress): ToolAddress {
	if ("path" in requested) {
		return requested;
	}
	const bound = listening.address();
	if (bound === null || typeof bound === "string") {
		throw new Error("the tools side did not listen on TCP");
	}
	return { host: requested.host, port: bound.port };
}
