import { expect, it } from "vitest";

import { cacheReadShare } from "./review-cache-usage";

it("weights attempts by all input tokens, including cache writes", () => {
	expect(
		cacheReadShare([
			{ inputTokens: 100, cacheReadTokens: 900, cacheWriteTokens: 0 },
			{ inputTokens: 1000, cacheReadTokens: 0, cacheWriteTokens: 1000 },
		]),
	).toBe(0.3);
});

it("separates no recorded input from recorded input with no reported cache reads", () => {
	expect(cacheReadShare([])).toBeUndefined();
	expect(
		cacheReadShare([{ inputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 }]),
	).toBeUndefined();
	expect(cacheReadShare([{ inputTokens: 100, cacheReadTokens: 0, cacheWriteTokens: 0 }])).toBe(0);
	expect(cacheReadShare([{ inputTokens: 0, cacheReadTokens: 100, cacheWriteTokens: 0 }])).toBe(1);
});
