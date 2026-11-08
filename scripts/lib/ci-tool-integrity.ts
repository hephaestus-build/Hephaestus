import { createHash } from "node:crypto";

export function toolSha256(bytes: Uint8Array): string {
	return createHash("sha256").update(bytes).digest("hex");
}

export function matchesToolPin(bytes: Uint8Array, pin: { sha256: string }): boolean {
	return toolSha256(bytes) === pin.sha256;
}
