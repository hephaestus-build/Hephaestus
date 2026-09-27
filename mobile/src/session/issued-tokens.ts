import type { IssuedTokens } from "./session-core";

// The access token is a signed JWT: three base64url segments. The refresh secret is opaque random
// base64url. A value of neither shape is not a credential this app will keep.
const JWT = /^[\w-]+\.[\w-]+\.[\w-]+$/u;
const SECRET = /^[\w-]{32,512}$/u;
const UUID = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/iu;

function instant(value: unknown, now: number): number | undefined {
	const time = value instanceof Date ? value.getTime() : Number.NaN;
	return Number.isFinite(time) && time > now ? time : undefined;
}

/**
 * The token response as the app relies on it, checked field by field: it arrives from a server the
 * person typed in, so a malformed or already expired answer is no session rather than a broken one.
 */
export function issuedTokens(dto: unknown, now: number = Date.now()): IssuedTokens | undefined {
	if (typeof dto !== "object" || dto === null) {
		return undefined;
	}
	const nativeSessionId = "nativeSessionId" in dto ? dto.nativeSessionId : undefined;
	const accessToken = "accessToken" in dto ? dto.accessToken : undefined;
	const refreshToken = "refreshToken" in dto ? dto.refreshToken : undefined;
	const accessTokenExpiresAt = instant(
		"accessTokenExpiresAt" in dto ? dto.accessTokenExpiresAt : undefined,
		now,
	);
	const sessionExpiresAt = instant(
		"sessionExpiresAt" in dto ? dto.sessionExpiresAt : undefined,
		now,
	);
	if (
		typeof nativeSessionId !== "string" ||
		!UUID.test(nativeSessionId) ||
		typeof accessToken !== "string" ||
		!JWT.test(accessToken) ||
		typeof refreshToken !== "string" ||
		!SECRET.test(refreshToken) ||
		accessTokenExpiresAt === undefined ||
		sessionExpiresAt === undefined
	) {
		return undefined;
	}
	return { nativeSessionId, accessToken, refreshToken, accessTokenExpiresAt, sessionExpiresAt };
}
