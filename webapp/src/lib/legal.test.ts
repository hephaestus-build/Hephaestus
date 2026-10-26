import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import { isSafeLegalHref, isSafeLegalImageSrc, resolveLegalContent } from "./legal";

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

function requestUrl(input: string | URL | Request): string {
	if (typeof input === "string") {
		return input;
	}
	return input instanceof URL ? input.href : input.url;
}

describe("resolveLegalContent", () => {
	let requestedUrls: string[] = [];

	beforeEach(() => {
		requestedUrls = [];
		vi.spyOn(globalThis, "fetch");
	});

	afterEach(() => {
		vi.restoreAllMocks();
	});

	interface MockedFile {
		status: number;
		body?: string;
		contentType?: string;
	}

	/**
	 * Mounts `files` by exact URL. Anything else answers 404 — what an unmounted legal file looks
	 * like in production, and what the cascade walks past.
	 */
	function mockResponses(files: Record<string, MockedFile>) {
		vi.mocked(globalThis.fetch).mockImplementation(async (input) => {
			const url = requestUrl(input);
			requestedUrls.push(url);
			const { status, body = "", contentType = "text/markdown" } = files[url] ?? { status: 404 };
			return new Response(body, { status, headers: { "Content-Type": contentType } });
		});
	}

	it("prefers the override when present", async () => {
		mockResponses({ "/legal-overrides/privacy.md": { status: 200, body: "# override privacy" } });
		const resolved = await resolveLegalContent("privacy");
		expect(resolved.source).toBe("override");
		expect(resolved.markdown).toContain("override privacy");
	});

	it("falls through to the disclaimer when no override is mounted", async () => {
		mockResponses({ "/legal/_disclaimer/imprint.md": { status: 200, body: "# fallback" } });
		const resolved = await resolveLegalContent("imprint");
		expect(resolved.source).toBe("disclaimer");
		expect(requestedUrls).toStrictEqual([
			"/legal-overrides/imprint.md",
			"/legal/_disclaimer/imprint.md",
		]);
	});

	it("rejects SPA-fallback HTML responses so the cascade continues", async () => {
		mockResponses({
			"/legal-overrides/privacy.md": {
				status: 200,
				body: "<!doctype html><html></html>",
				contentType: "text/html",
			},
			"/legal/_disclaimer/privacy.md": { status: 200, body: "# fallback" },
		});
		const resolved = await resolveLegalContent("privacy");
		expect(resolved.source).toBe("disclaimer");
	});

	// Some reverse proxies rewrite misses to a 200 with an empty body instead of
	// the SPA fallback. The cascade must keep walking or we'd render a blank page.
	it("rejects empty/whitespace-only bodies so the cascade continues", async () => {
		mockResponses({
			"/legal-overrides/privacy.md": { status: 200, body: "   \n\t\n  " },
			"/legal/_disclaimer/privacy.md": { status: 200, body: "# fallback" },
		});
		const resolved = await resolveLegalContent("privacy");
		expect(resolved.source).toBe("disclaimer");
	});

	it("re-throws AbortError as a DOMException so teardown is distinguishable", async () => {
		vi.mocked(globalThis.fetch).mockImplementation(async () => {
			throw new DOMException("aborted", "AbortError");
		});
		const rejection: unknown = await resolveLegalContent("privacy").catch(
			(error: unknown) => error,
		);
		assert(rejection instanceof DOMException);
		expect(rejection.name).toBe("AbortError");
	});
});
