import type { ReviewUsageAttempt } from "@/api/types.gen";

/** Input, cache reads and cache writes are disjoint buckets in the usage ledger. */
export function cacheReadShare(
	attempts: readonly Pick<
		ReviewUsageAttempt,
		"inputTokens" | "cacheReadTokens" | "cacheWriteTokens"
	>[],
): number | undefined {
	let read = 0;
	let input = 0;
	for (const attempt of attempts) {
		read += attempt.cacheReadTokens;
		input += attempt.inputTokens + attempt.cacheReadTokens + attempt.cacheWriteTokens;
	}
	return input === 0 ? undefined : read / input;
}
