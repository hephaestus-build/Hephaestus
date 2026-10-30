/** Bounded durations from SDK execution events; tool names, arguments and answers are never retained. */
export class ToolCallTimings {
	private readonly starts = new Map<string, number>();
	private readonly spans: { durationMs: number; outcome: "completed" | "failed" }[] = [];
	private omitted = 0;

	observe(
		event: {
			type: string;
			toolCallId?: string;
			isError?: boolean;
		},
		now = performance.now(),
	) {
		if (
			(event.type !== "tool_execution_start" && event.type !== "tool_execution_end") ||
			event.toolCallId === undefined
		) {
			return;
		}
		if (event.type === "tool_execution_start") {
			if (this.starts.has(event.toolCallId) || this.starts.size + this.spans.length >= 4096) {
				this.omitted += 1;
				return;
			}
			this.starts.set(event.toolCallId, now);
			return;
		}
		const started = this.starts.get(event.toolCallId);
		this.starts.delete(event.toolCallId);
		if (started === undefined) {
			return;
		}
		const duration = now - started;
		if (Number.isFinite(duration) && duration >= 0) {
			this.spans.push({
				durationMs: Math.round(duration * 1000) / 1000,
				outcome: event.isError === true ? "failed" : "completed",
			});
		} else {
			this.omitted += 1;
		}
	}

	snapshot() {
		return {
			spans: [...this.spans],
			unfinished: this.starts.size,
			omitted: this.omitted,
		};
	}
}
