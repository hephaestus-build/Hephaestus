export interface GatewayCapabilities {
	workspaceByteBudget: number;
	frameByteBudget: number | null;
}

const PROTOCOL_VERSION = 3;

function isByteBudget(value: unknown): value is number {
	return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

/** What the gateway advertises for this session, refused whole when any field is not as declared. */
export async function discoverCapabilities(
	endpoint: string,
	headers: Record<string, string>,
): Promise<GatewayCapabilities> {
	const discovery = await fetch(endpoint, { headers });
	if (!discovery.ok) throw new Error(`Gateway discovery refused: ${discovery.status}`);
	const capabilities: unknown = await discovery.json();
	if (
		typeof capabilities !== "object" ||
		capabilities === null ||
		!("protocolVersion" in capabilities) ||
		capabilities.protocolVersion !== PROTOCOL_VERSION ||
		!("workspaceByteBudget" in capabilities) ||
		!isByteBudget(capabilities.workspaceByteBudget)
	)
		throw new Error("Invalid gateway capabilities");
	// Absent on the wire for a session with no interactive channel.
	const frameByteBudget = "frameByteBudget" in capabilities ? capabilities.frameByteBudget : null;
	if (frameByteBudget !== null && !isByteBudget(frameByteBudget))
		throw new Error("Invalid gateway capabilities");
	return {
		workspaceByteBudget: capabilities.workspaceByteBudget,
		frameByteBudget,
	};
}
