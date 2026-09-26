import { assert, describe, expect, it } from "vitest";

import { connectionSchema, workspaceDetailsSchema } from "@/components/create-workspace/schemas";

describe("connectionSchema", () => {
	it("accepts a PAT and trims it", () => {
		const result = connectionSchema.safeParse({ personalAccessToken: "  glpat-abc123  " });
		assert(result.success);
		expect(result.data.personalAccessToken).toBe("glpat-abc123");
	});

	it("rejects empty PAT", () => {
		const result = connectionSchema.safeParse({ personalAccessToken: "" });
		expect(result.success).toBe(false);
	});

	it("rejects whitespace-only PAT", () => {
		const result = connectionSchema.safeParse({ personalAccessToken: "   " });
		expect(result.success).toBe(false);
	});
});

describe("workspaceDetailsSchema", () => {
	it("accepts valid display name and slug", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "My Workspace",
			workspaceSlug: "my-workspace",
		});
		expect(result.success).toBe(true);
	});

	it("rejects empty display name", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "",
			workspaceSlug: "valid-slug",
		});
		expect(result.success).toBe(false);
	});

	it("rejects display name over 120 chars", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "a".repeat(121),
			workspaceSlug: "valid-slug",
		});
		expect(result.success).toBe(false);
	});

	it("rejects uppercase slug", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "INVALID",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug with special characters", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "invalid_slug!",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug shorter than 3 characters", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "ab",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug longer than 51 characters", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: `a${"b".repeat(51)}`,
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug starting with hyphen", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "-invalid",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug ending with hyphen", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "invalid-",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug with consecutive hyphens", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "my--workspace",
		});
		expect(result.success).toBe(false);
	});

	it("accepts slug starting with digit", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "1-valid-slug",
		});
		expect(result.success).toBe(true);
	});

	it("accepts slug at exactly 3 characters", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "abc",
		});
		expect(result.success).toBe(true);
	});

	it("accepts slug at exactly 51 characters", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: `a${"b".repeat(50)}`,
		});
		expect(result.success).toBe(true);
	});

	it("trims whitespace from display name", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "  My Workspace  ",
			workspaceSlug: "my-workspace",
		});
		assert(result.success);
		expect(result.data.displayName).toBe("My Workspace");
	});

	it("rejects whitespace-only display name", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "   ",
			workspaceSlug: "valid-slug",
		});
		expect(result.success).toBe(false);
	});

	it("accepts all-digit slug", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "123",
		});
		expect(result.success).toBe(true);
	});

	it("rejects slug with underscores", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "my_workspace",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug with periods", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "my.workspace",
		});
		expect(result.success).toBe(false);
	});

	it("rejects slug with spaces", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "my workspace",
		});
		expect(result.success).toBe(false);
	});

	it("trims whitespace from slug before validation", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "  valid-slug  ",
		});
		expect(result.success).toBe(true);
	});

	it("rejects empty slug", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "",
		});
		expect(result.success).toBe(false);
	});

	it("provides targeted error for slug starting with hyphen", () => {
		const result = workspaceDetailsSchema.safeParse({
			displayName: "Test",
			workspaceSlug: "-abc",
		});
		assert(!result.success);
		const slugErrors = result.error.issues.filter((i) => i.path[0] === "workspaceSlug");
		expect(slugErrors.some((e) => e.message.includes("start with"))).toBe(true);
	});
});
