import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import { resolveLegalContent } from "./resolve-legal-content";

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
