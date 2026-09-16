import assert from "node:assert/strict";
import { Blob as NodeBlob } from "node:buffer";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { copyReviewLinks, reviewLinkUrl } from "./review-links";

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");
const write = vi.fn<Clipboard["write"]>();
const writeText = vi.fn<Clipboard["writeText"]>();

beforeEach(() => {
	write.mockReset().mockResolvedValue();
	writeText.mockReset().mockResolvedValue();
	Object.defineProperty(navigator, "clipboard", {
		configurable: true,
		value: { write, writeText },
	});
	vi.stubGlobal("Blob", NodeBlob);
	vi.stubGlobal(
		"ClipboardItem",
		class {
			constructor(private readonly data: Record<string, Blob>) {}
			async getType(type: string) {
				const blob = this.data[type];
				if (!blob) {
					throw new Error(`Missing clipboard format: ${type}`);
				}
				return blob;
			}
		},
	);
});

afterEach(() => {
	vi.unstubAllGlobals();
	if (originalClipboard) {
		Object.defineProperty(navigator, "clipboard", originalClipboard);
	} else {
		Reflect.deleteProperty(navigator, "clipboard");
	}
});

describe("review links", () => {
	it.each([
		undefined,
		"",
		"/relative",
		"javascript:alert(1)",
		"data:text/html,test",
		"https://token@example.com/pr/1",
	])("does not activate or copy an unsafe URL: %s", (url) => {
		expect(reviewLinkUrl(url)).toBeUndefined();
	});

	it("accepts absolute web links for hosted and self-hosted providers", () => {
		expect(reviewLinkUrl("https://gitlab.example.com/team/repo/-/merge_requests/2")).toBe(
			"https://gitlab.example.com/team/repo/-/merge_requests/2",
		);
		expect(reviewLinkUrl("http://git.internal/team/repo/pull/1")).toBe(
			"http://git.internal/team/repo/pull/1",
		);
	});

	it("serializes repository text without interpreting HTML and preserves plain URLs", async () => {
		const label = '<img src=x onerror="alert(1)"> & repository #1';
		const url = new URL('https://github.com/org/repo/pull/1?q="&other=value').href;
		await copyReviewLinks([{ label, url }]);
		const item = write.mock.calls[0]?.[0][0];
		assert.ok(item);
		const htmlBlob = await item.getType("text/html");
		const html = new DOMParser().parseFromString(await htmlBlob.text(), "text/html");
		expect(html.querySelector("img")).toBeNull();
		expect(html.querySelectorAll("a")).toHaveLength(1);
		expect(html.querySelector("a")?.textContent).toBe(label);
		expect(html.querySelector("a")?.getAttribute("href")).toBe(url);
		const text = await item.getType("text/plain");
		await expect(text.text()).resolves.toBe(url);
	});

	it("falls back to plain text when rich copying is rejected", async () => {
		write.mockRejectedValue(new Error("Rich clipboard denied"));
		await copyReviewLinks([
			{ label: "repo #1", url: "https://example.com/1" },
			{ label: "repo #2", url: "https://example.com/2" },
		]);
		expect(writeText).toHaveBeenCalledWith("https://example.com/1\nhttps://example.com/2");
	});

	it("propagates failure when neither format can be written", async () => {
		write.mockRejectedValue(new Error("Rich clipboard denied"));
		writeText.mockRejectedValue(new Error("Clipboard denied"));
		await expect(
			copyReviewLinks([{ label: "repo #1", url: "https://example.com/1" }]),
		).rejects.toThrow("Clipboard denied");
	});

	it("uses plain text when ClipboardItem is unavailable", async () => {
		vi.stubGlobal("ClipboardItem", undefined);
		await copyReviewLinks([{ label: "repo #1", url: "https://example.com/1" }]);
		expect(writeText).toHaveBeenCalledWith("https://example.com/1");
	});
});
