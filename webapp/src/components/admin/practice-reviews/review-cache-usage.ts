import type { ReviewUsageAttempt } from "@/api/types.gen";

/** Input, cache reads and cache writes are disjoint buckets in the usage ledger. */
export function reviewCacheUsage(
	attempts: readonly Pick<
		ReviewUsageAttempt,
		"inputTokens" | "cacheReadTokens" | "cacheWriteTokens"
	>[],
) {
	let read = 0;
	let written = 0;
	let input = 0;
	for (const attempt of attempts) {
		read += attempt.cacheReadTokens;
		written += attempt.cacheWriteTokens;
		input += attempt.inputTokens + attempt.cacheReadTokens + attempt.cacheWriteTokens;
	}
	return { read, written, input, readShare: input === 0 ? undefined : read / input };
}
