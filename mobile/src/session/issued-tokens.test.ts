import { describe, expect, it } from "vitest";

import { issuedTokens } from "./issued-tokens";

const NOW = Date.UTC(2026, 8, 26, 12);
const valid = {
	nativeSessionId: "0f8b4c7e-2a4d-4e1b-9c3a-5d6e7f8a9b0c",
	accessToken: "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl",
	refreshToken: "Q2xhdWRlIHdhcyBoZXJlIGJ1dCB0aGlzIGlzIGEgdGVzdA",
	accessTokenExpiresAt: new Date(NOW + 3_600_000),
	sessionExpiresAt: new Date(NOW + 7 * 86_400_000),
};

describe("issuedTokens", () => {
	it("keeps a complete answer", () => {
		expect(issuedTokens(valid, NOW)).toStrictEqual({
			nativeSessionId: valid.nativeSessionId,
			accessToken: valid.accessToken,
			refreshToken: valid.refreshToken,
			accessTokenExpiresAt: NOW + 3_600_000,
			sessionExpiresAt: NOW + 7 * 86_400_000,
		});
	});

	it.each([
		["no body", undefined],
		["no session id", { ...valid, nativeSessionId: undefined }],
		["a session id that is not a UUID", { ...valid, nativeSessionId: "session-1" }],
		["an empty access token", { ...valid, accessToken: "" }],
		["an access token that is not a JWT", { ...valid, accessToken: "not a token" }],
		["a missing refresh secret", { ...valid, refreshToken: undefined }],
		["a refresh secret too short to be random", { ...valid, refreshToken: "abc" }],
		["a refresh secret with whitespace", { ...valid, refreshToken: `${valid.refreshToken} x` }],
		["an unreadable expiry", { ...valid, accessTokenExpiresAt: new Date(Number.NaN) }],
		["an expiry that is a string", { ...valid, sessionExpiresAt: "2026-10-03T12:00:00Z" }],
		["a session that already ended", { ...valid, sessionExpiresAt: new Date(NOW - 1) }],
	])("refuses %s", (_case, dto) => {
		expect(issuedTokens(dto, NOW)).toBeUndefined();
	});
});
