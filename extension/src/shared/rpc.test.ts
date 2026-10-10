import { describe, expect, it } from "vitest";

import { requestSchema } from "./rpc";

describe("workspace RPC selectors", () => {
	it.each(["abc", "123", "a".repeat(51)])("accepts the workspace slug %s", (workspaceSlug) => {
		expect(requestSchema.safeParse({ type: "list-observations", workspaceSlug }).success).toBe(
			true,
		);
	});

	it.each(["a", "ab", "a".repeat(52), "team-", "a--b", "xn--test"])(
		"rejects the invalid workspace slug %s",
		(workspaceSlug) => {
			expect(requestSchema.safeParse({ type: "list-observations", workspaceSlug }).success).toBe(
				false,
			);
		},
	);
});
