import { describe, expect, it } from "vitest";

import { isSafeLegalHref, isSafeLegalImageSrc } from "./legal";

describe("isSafeLegalHref / isSafeLegalImageSrc", () => {
	it("allows http(s), mailto, tel, fragment, and absolute paths", () => {
		expect(isSafeLegalHref("https://example.org")).toBe(true);
		expect(isSafeLegalHref("http://example.org")).toBe(true);
		expect(isSafeLegalHref("mailto:dpo@example.org")).toBe(true);
		expect(isSafeLegalHref("tel:+4989")).toBe(true);
		expect(isSafeLegalHref("#section")).toBe(true);
		expect(isSafeLegalHref("/privacy")).toBe(true);
	});

	it("rejects javascript:, data:, vbscript:, and unknown schemes", () => {
		expect(isSafeLegalHref("javascript:alert(1)")).toBe(false);
		expect(isSafeLegalHref(" javascript:alert(1)")).toBe(false);
		expect(isSafeLegalHref("data:text/html,<script>alert(1)</script>")).toBe(false);
		expect(isSafeLegalHref("vbscript:msgbox(1)")).toBe(false);
		expect(isSafeLegalHref("file:///etc/passwd")).toBe(false);
		expect(isSafeLegalHref(null)).toBe(false);
		expect(isSafeLegalHref(undefined)).toBe(false);
	});

	// Scheme-relative URLs (`//host/...`) inherit the page's scheme but hit an
	// arbitrary origin, so they must be blocked even though the leading `/`
	// looks like an absolute path.
	it("rejects scheme-relative URLs that would escape the origin", () => {
		expect(isSafeLegalHref("//evil.com/pwn")).toBe(false);
		expect(isSafeLegalHref("///evil.com/pwn")).toBe(false);
		expect(isSafeLegalImageSrc("//evil.com/x.png")).toBe(false);
	});

	it("images must be http(s) or an absolute path; data-URIs are rejected", () => {
		expect(isSafeLegalImageSrc("https://example.org/logo.png")).toBe(true);
		expect(isSafeLegalImageSrc("/logo.png")).toBe(true);
		expect(isSafeLegalImageSrc("data:image/png;base64,AAA")).toBe(false);
		expect(isSafeLegalImageSrc("javascript:alert(1)")).toBe(false);
	});
});
