/** A calm reading pace for guidance, in words a minute. */
const WORDS_PER_MINUTE = 200;

/** The words a reader reads in a piece of text: runs of letters or digits, apostrophes kept. */
export function countWords(text: string): number {
	return text.match(/[\p{L}\p{N}][\p{L}\p{N}'’-]*/gu)?.length ?? 0;
}

/**
 * The words a guide shows, out of its markdown: an image is a figure, not text, so it counts for
 * nothing, and a link counts by its words, not its address. That holds for reference-style links
 * too: their definitions (`[ref]: https://… "title"`) are not shown, and a bare address is read as
 * a link, not as words.
 */
export function guideWords(markdown: string): number {
	const text = markdown
		.replaceAll(
			/^ {0,3}\[[^\]]+\]:[ \t]*\S+(?:[ \t]+(?:"[^"]*"|'[^']*'|\([^)]*\)))?[ \t]*$/gmu,
			" ",
		)
		.replaceAll(/!\[[^\]]*\](?:\([^)]*\)|\[[^\]]*\])/gu, " ")
		.replaceAll(/\[(?<words>[^\]]*)\](?:\([^)]*\)|\[[^\]]*\])/gu, "$<words>")
		.replaceAll(/<[^>]+>/gu, " ")
		.replaceAll(/\b(?:https?:\/\/|www\.)\S+/gu, " ");
	return countWords(text);
}

/** "1 min read" and up: whole minutes, rounded up, never less than one. */
export function readTime(words: number): string {
	return `${Math.max(1, Math.ceil(words / WORDS_PER_MINUTE))} min read`;
}
