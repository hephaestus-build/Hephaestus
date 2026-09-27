/** RFC 4648 §5 base64url without padding, as RFC 7636 requires for the verifier and challenge. */
export function base64Url(bytes: Uint8Array): string {
	let binary = "";
	for (const byte of bytes) {
		binary += String.fromCodePoint(byte);
	}
	return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export interface Pkce {
	verifier: string;
	challenge: string;
	state: string;
}

/**
 * A fresh PKCE pair and `state` for one sign-in. The platform supplies randomness and SHA-256 so this
 * stays testable off-device; 32 random bytes give the 43-character verifier the server expects.
 */
export async function createPkce(
	randomBytes: (length: number) => Uint8Array,
	sha256: (ascii: string) => Promise<Uint8Array>,
): Promise<Pkce> {
	const verifier = base64Url(randomBytes(32));
	const challenge = base64Url(await sha256(verifier));
	return { verifier, challenge, state: base64Url(randomBytes(16)) };
}
