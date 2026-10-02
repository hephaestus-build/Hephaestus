import { describe, expect, it } from "vitest";

import { randomToken, s256Challenge } from "~/shared/pkce";

describe("pkce", () => {
	it("makes a 43-character verifier inside the server's accepted alphabet", () => {
		expect(randomToken()).toMatch(/^[A-Za-z0-9_-]{43}$/u);
	});

	it("derives the RFC 7636 appendix B challenge", async () => {
		await expect(s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).resolves.toBe(
			"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
		);
	});
});
