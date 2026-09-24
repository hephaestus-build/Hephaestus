export function sanitizeMessageText(text: string) {
	// Strip ALL occurrences — a single streamed reply can contain the marker more than once, and a
	// String.replace without the global flag would leave the rest visible to the user.
	return text.replaceAll("<has_function_call>", "");
}
