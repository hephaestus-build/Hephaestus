import { expect, it } from "vitest";

import { reviewCacheUsage } from "./review-cache-usage";

it("weights attempts by all input tokens, including cache writes", () => {
	expect(
		reviewCacheUsage([
			{ inputTokens: 100, cacheReadTokens: 900, cacheWriteTokens: 0 },
			{ inputTokens: 1000, cacheReadTokens: 0, cacheWriteTokens: 1000 },
		]),
	).toStrictEqual({ read: 900, written: 1000, input: 3000, readShare: 0.3 });
});

it("separates no recorded input from recorded input with no reported cache reads", () => {
	expect(reviewCacheUsage([]).readShare).toBeUndefined();
	expect(
		reviewCacheUsage([{ inputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 }]).readShare,
	).toBeUndefined();
	expect(
		reviewCacheUsage([{ inputTokens: 100, cacheReadTokens: 0, cacheWriteTokens: 0 }]).readShare,
	).toBe(0);
	expect(
		reviewCacheUsage([{ inputTokens: 0, cacheReadTokens: 100, cacheWriteTokens: 0 }]).readShare,
	).toBe(1);
});
