/**
 * A mentor turn succeeded only when its stream carries a non-error finish chunk before the `[DONE]`
 * sentinel; the server sends `[DONE]` to terminate an error response too.
 * @param {unknown} body - the response body; only text can carry a completed stream
 * @returns {boolean} whether the turn finished cleanly
 */
export function mentorStreamCompleted(body) {
	if (typeof body !== "string") {
		return false;
	}
	let finished = false;
	let done = false;
	for (const line of body.split(/\r?\n/u)) {
		if (!line.startsWith("data:")) {
			continue;
		}
		if (done) {
			return false;
		}
		const data = line.slice(5).trim();
		if (data === "[DONE]") {
			done = true;
			continue;
		}
		try {
			/** @type {unknown} */
			const chunk = JSON.parse(data);
			if (!isStreamChunk(chunk)) {
				return false;
			}
			if (["error", "abort", "tool-output-error"].includes(chunk.type)) {
				return false;
			}
			if (chunk.type === "finish") {
				if (chunk.finishReason === "error" || chunk.finishReason === "content-filter") {
					return false;
				}
				finished = true;
			}
		} catch {
			return false;
		}
	}
	return finished && done;
}

/**
 * @param {unknown} chunk - one parsed `data:` line of the stream
 * @returns {chunk is { type: string; finishReason?: unknown }} whether it is a chunk with a typed
 * kind; the server writes nothing else
 */
function isStreamChunk(chunk) {
	return (
		typeof chunk === "object" && chunk !== null && "type" in chunk && typeof chunk.type === "string"
	);
}
