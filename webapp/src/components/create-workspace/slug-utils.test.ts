import { describe, expect, it } from "vitest";

import { generateSlug } from "@/components/create-workspace/slug-utils";

describe("generateSlug", () => {
	it("converts spaces to hyphens and lowercases", () => {
		expect(generateSlug("My Workspace")).toBe("my-workspace");
	});

	it("strips special characters", () => {
		expect(generateSlug("Hello World! @#$%")).toBe("hello-world");
	});

	it("collapses consecutive hyphens", () => {
		expect(generateSlug("a---b")).toBe("a-b");
	});

	it("removes leading hyphens", () => {
		expect(generateSlug("---abc")).toBe("abc");
	});

	it("removes trailing hyphens", () => {
		expect(generateSlug("abc---")).toBe("abc");
	});

	it("truncates to 51 characters", () => {
		const long = "a".repeat(100);
		expect(generateSlug(long).length).toBeLessThanOrEqual(51);
	});

	it("strips trailing hyphens introduced by truncation", () => {
		// 50 chars of "a" + "-b" = 52 chars → truncated to 51 → ends with hyphen → stripped
		const input = `${"a".repeat(50)}-b`;
		const slug = generateSlug(input);
		expect(slug).not.toMatch(/-$/u);
		expect(slug.length).toBeLessThanOrEqual(51);
	});

	it("returns empty string for empty input", () => {
		expect(generateSlug("")).toBe("");
	});

	it("returns empty string for only special chars", () => {
		expect(generateSlug("!@#$%")).toBe("");
	});

	it("passes through an already-valid slug", () => {
		expect(generateSlug("my-valid-slug")).toBe("my-valid-slug");
	});

	it("handles unicode characters with diacritic normalization", () => {
		expect(generateSlug("Ünïcödé Tëst")).toBe("unicode-test");
	});

	it("handles digits at start", () => {
		expect(generateSlug("123 Project")).toBe("123-project");
	});

	it("handles input that is exactly 51 chars after processing", () => {
		const input = "a".repeat(51);
		expect(generateSlug(input)).toBe("a".repeat(51));
	});

	it("strips CJK characters entirely", () => {
		expect(generateSlug("测试")).toBe("");
	});

	it("strips emoji to empty", () => {
		expect(generateSlug("🚀🔥")).toBe("");
	});

	it("is idempotent", () => {
		const inputs = ["My Workspace", "hello--world", "  spaces  ", "Ünïcödé"];
		for (const input of inputs) {
			const once = generateSlug(input);
			const twice = generateSlug(once);
			expect(twice).toBe(once);
		}
	});
});
