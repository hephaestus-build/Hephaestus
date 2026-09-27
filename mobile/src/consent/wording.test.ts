import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { ANSWERS, RESEARCH, TERMS, TERMS_ACCEPTANCE, WORDING_VERSION } from "./wording";

const web = readFileSync(
	new URL("../../../webapp/src/components/auth/ConsentPage.tsx", import.meta.url),
	"utf8",
);

describe("transparency notice wording", () => {
	it("is the version the web app archives", () => {
		expect(web).toContain(`export const WORDING_VERSION = "${WORDING_VERSION}";`);
	});

	it("says what the web app says, in the same words", () => {
		const collapsed = web.replaceAll(/\s+/gu, " ");
		for (const { term, detail } of [...TERMS, ...RESEARCH]) {
			expect(collapsed).toContain(`term: "${term}"`);
			// The web version links "privacy notice" inside the sentence; the words around it match.
			for (const part of detail.split("privacy notice")) {
				expect(collapsed).toContain(part.trim().replaceAll("’", "'"));
			}
		}
		for (const answer of ANSWERS) {
			expect(collapsed).toContain(answer.title);
			expect(collapsed).toContain(answer.detail);
		}
		expect(collapsed).toContain(TERMS_ACCEPTANCE);
	});
});
