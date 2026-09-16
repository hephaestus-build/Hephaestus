import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	isSafeLegalHref,
	isSafeLegalImageSrc,
	isValidLegalProfile,
	LEGAL_PROFILE_PATTERN_SOURCE,
	resolveLegalContent,
} from "./legal";

const TUMAET_PROFILE_MARKERS = [
	"Technical University of Munich",
	"85748 Garching",
	"Prof. Dr. Stephan Krusche",
	"ls1.admin@in.tum.de",
];

describe("isValidLegalProfile", () => {
	it("accepts lowercase alphanumerics, dashes, underscores", () => {
		expect(isValidLegalProfile("tumaet")).toBe(true);
		expect(isValidLegalProfile("my-fork_01")).toBe(true);
		expect(isValidLegalProfile("a")).toBe(true);
	});

	it("rejects path-traversal, whitespace, uppercase, and exotic characters", () => {
		expect(isValidLegalProfile("")).toBe(false);
		expect(isValidLegalProfile("..")).toBe(false);
		expect(isValidLegalProfile("../admin")).toBe(false);
		expect(isValidLegalProfile("tumaet/../admin")).toBe(false);
		expect(isValidLegalProfile("a b")).toBe(false);
		expect(isValidLegalProfile("-tumaet")).toBe(false);
		expect(isValidLegalProfile("TUMAET")).toBe(false);
		expect(isValidLegalProfile("a".repeat(33))).toBe(false);
	});

	// The same pattern is duplicated in webapp/docker/entrypoint.sh so the
	// container can warn operators before the browser ever loads. Pin the
	// string verbatim so a widening of one side can't drift from the other.
	it("exposes the exact regex source shared with entrypoint.sh", () => {
		expect(LEGAL_PROFILE_PATTERN_SOURCE).toBe("^[a-z0-9][a-z0-9_-]{0,31}$");
	});
});

describe("isSafeLegalHref / isSafeLegalImageSrc", () => {
	it("allows http(s), mailto, tel, fragment, and absolute paths", () => {
		expect(isSafeLegalHref("https://tum.de")).toBe(true);
		expect(isSafeLegalHref("http://example.org")).toBe(true);
		expect(isSafeLegalHref("mailto:dpo@tum.de")).toBe(true);
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
		expect(isSafeLegalImageSrc("https://tum.de/logo.png")).toBe(true);
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
	 * Mounts `files` by exact URL and `directories` by path prefix. Anything neither names answers
	 * 404 — what an unmounted legal file looks like in production, and what the cascade walks past.
	 */
	function mockResponses(
		files: Record<string, MockedFile>,
		directories: Record<string, MockedFile> = {},
	) {
		vi.mocked(globalThis.fetch).mockImplementation(async (input) => {
			const url = requestUrl(input);
			requestedUrls.push(url);
			const mounted = Object.entries(directories).find(([prefix]) => url.startsWith(prefix));
			const {
				status,
				body = "",
				contentType = "text/markdown",
			} = files[url] ?? mounted?.[1] ?? { status: 404 };
			return new Response(body, { status, headers: { "Content-Type": contentType } });
		});
	}

	it("prefers the override when present", async () => {
		mockResponses({ "/legal-overrides/privacy.md": { status: 200, body: "# override privacy" } });
		const resolved = await resolveLegalContent("privacy", { profile: "tumaet" });
		expect(resolved.source).toBe("override");
		expect(resolved.markdown).toContain("override privacy");
	});

	it("falls through to the profile when no override is mounted", async () => {
		mockResponses({
			"/legal/profiles/tumaet/privacy.md": { status: 200, body: "# profile privacy" },
		});
		const resolved = await resolveLegalContent("privacy", { profile: "tumaet" });
		expect(resolved.source).toBe("profile");
	});

	it("falls through to disclaimer when the profile has no file", async () => {
		mockResponses({ "/legal/_disclaimer/imprint.md": { status: 200, body: "# fallback" } });
		const resolved = await resolveLegalContent("imprint", { profile: "unknown-profile" });
		expect(resolved.source).toBe("disclaimer");
	});

	it("rejects SPA-fallback HTML responses so the cascade continues", async () => {
		mockResponses({
			"/legal-overrides/privacy.md": {
				status: 200,
				body: "<!doctype html><html></html>",
				contentType: "text/html",
			},
			"/legal/profiles/tumaet/privacy.md": { status: 200, body: "# real tumaet privacy" },
		});
		const resolved = await resolveLegalContent("privacy", { profile: "tumaet" });
		expect(resolved.source).toBe("profile");
	});

	// Some reverse proxies rewrite misses to a 200 with an empty body instead of
	// the SPA fallback. The cascade must keep walking or we'd render a blank page.
	it("rejects empty/whitespace-only bodies so the cascade continues", async () => {
		mockResponses({
			"/legal-overrides/privacy.md": { status: 200, body: "   \n\t\n  " },
			"/legal/profiles/tumaet/privacy.md": { status: 200, body: "# real tumaet privacy" },
		});
		const resolved = await resolveLegalContent("privacy", { profile: "tumaet" });
		expect(resolved.source).toBe("profile");
	});

	it("re-throws AbortError as a DOMException so teardown is distinguishable", async () => {
		vi.mocked(globalThis.fetch).mockImplementation(async () => {
			throw new DOMException("aborted", "AbortError");
		});
		const rejection: unknown = await resolveLegalContent("privacy", { profile: "tumaet" }).catch(
			(error: unknown) => error,
		);
		assert(rejection instanceof DOMException);
		expect(rejection.name).toBe("AbortError");
	});

	it("invalid profile values fall through to the disclaimer without constructing profile URLs", async () => {
		mockResponses({ "/legal/_disclaimer/privacy.md": { status: 200, body: "# fallback" } });
		const resolved = await resolveLegalContent("privacy", { profile: "../etc" });
		expect(resolved.source).toBe("disclaimer");
		expect(resolved.profile).toBe("");
		expect(requestedUrls.some((u) => u.startsWith("/legal/profiles/"))).toBe(false);
	});

	it("non-tumaet profiles must not leak TUM canonical identity markers", async () => {
		mockResponses(
			{
				"/legal/_disclaimer/privacy.md": {
					status: 200,
					body: "# Privacy statement not configured",
				},
				"/legal/_disclaimer/imprint.md": { status: 200, body: "# Imprint not configured" },
			},
			{ "/legal/profiles/": { status: 200, body: "<!doctype html>", contentType: "text/html" } },
		);
		for (const page of ["privacy", "imprint"] as const) {
			const resolved = await resolveLegalContent(page, { profile: "" });
			expect(resolved.source).toBe("disclaimer");
			for (const marker of TUMAET_PROFILE_MARKERS) {
				expect(resolved.markdown).not.toContain(marker);
			}
		}
		expect(requestedUrls.some((u) => u.startsWith("/legal-overrides/"))).toBe(true);
	});
});
