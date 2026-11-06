// Tool protocol v1 over TCP, as a tools Pod serves it: the whole suite of tool-protocol.spec.ts on the other transport.
process.env.TOOL_PROTOCOL_TRANSPORT = "tcp";
await import("./tool-protocol.spec.ts");
