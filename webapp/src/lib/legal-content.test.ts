// oxlint-disable import/no-relative-parent-imports -- The shipped legal markdown lives in
// `public/`, which Vite serves as a static asset and no `@/` alias reaches. Asserting the real
// files is the point of this suite; importing a copy under `src/` would test the copy.
import { describe, expect, it } from "vitest";

// Source of truth for every Markdown file we ship. Each is imported as a
// raw string so the test runs inside jsdom (no node:fs) and any
// addition/removal of a layer will trigger a type error here first.
import disclaimerImprint from "../../public/legal/_disclaimer/imprint.md?raw";
import disclaimerPrivacy from "../../public/legal/_disclaimer/privacy.md?raw";

const DISCLAIMER = {
	"imprint.md": disclaimerImprint,
	"privacy.md": disclaimerPrivacy,
};

describe("legal content layout", () => {
	it("ships imprint and privacy for the disclaimer", () => {
		for (const [name, body] of Object.entries(DISCLAIMER)) {
			expect(body.length, name).toBeGreaterThan(100);
		}
	});

	// Byte-count checks prove *something* shipped but not that the fallback
	// text carries the statutory basis visitors and auditors are looking for.
	// Pin the actual obligation markers so any future simplification that
	// drops them fails loudly.
	it("disclaimer imprint cites § 5 DDG as the triggering obligation", () => {
		expect(disclaimerImprint).toContain("§ 5 DDG");
	});

	it("disclaimer privacy cites Art. 13 and Art. 14 GDPR and names the controller", () => {
		expect(disclaimerPrivacy).toContain("Art. 13");
		expect(disclaimerPrivacy).toContain("Art. 14");
		expect(disclaimerPrivacy).toContain("Art. 4(7)");
	});

	it("disclaimer points operators at the admin docs", () => {
		for (const [file, body] of Object.entries(DISCLAIMER)) {
			expect({ file, linked: body.includes("docs/admin/legal-pages") }).toStrictEqual({
				file,
				linked: true,
			});
		}
	});
});
