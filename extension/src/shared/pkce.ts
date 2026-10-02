/**
 * RFC 7636 proof key and the sign-in `state`. The verifier is 32 random bytes, base64url without
 * padding (43 characters, inside the server's `^[A-Za-z0-9._~-]{43,128}$`); the challenge is its
 * SHA-256, encoded the same way.
 */
export function base64Url(bytes: Uint8Array): string {
	let binary = "";
	for (const byte of bytes) {
		binary += String.fromCodePoint(byte);
	}
	return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function randomToken(byteLength = 32): string {
	return base64Url(crypto.getRandomValues(new Uint8Array(byteLength)));
}

export async function s256Challenge(verifier: string): Promise<string> {
	const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
	return base64Url(new Uint8Array(digest));
}
