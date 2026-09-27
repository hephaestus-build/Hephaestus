import { createHash, randomBytes } from "node:crypto";

import { describe, expect, it } from "vitest";

import { base64Url, createPkce } from "./pkce";

const random = (length: number): Uint8Array => new Uint8Array(randomBytes(length));

const sha256 = async (ascii: string): Promise<Uint8Array> =>
	new Uint8Array(createHash("sha256").update(ascii, "ascii").digest());

describe("createPkce", () => {
	it("derives the S256 challenge of RFC 7636 Appendix B", async () => {
		const verifierBytes = Buffer.from("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "base64url");
		const pkce = await createPkce(() => new Uint8Array(verifierBytes), sha256);

		expect(pkce.verifier).toBe("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
		expect(pkce.challenge).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
	});

	it("draws a new verifier and state every time", async () => {
		const first = await createPkce(random, sha256);
		const second = await createPkce(random, sha256);

		expect(first.verifier).toHaveLength(43);
		expect(first.verifier).not.toBe(second.verifier);
		expect(first.state).not.toBe(second.state);
	});
});

describe("base64Url", () => {
	it("uses the URL alphabet without padding", () => {
		expect(base64Url(new Uint8Array([251, 255, 191]))).toBe("-_-_");
		expect(base64Url(new Uint8Array([1]))).toBe("AQ");
	});
});
