import { createHash } from "node:crypto";
import {
	appendFileSync,
	copyFileSync,
	existsSync,
	mkdirSync,
	statSync,
	writeFileSync,
} from "node:fs";
import path from "node:path";

import type { AgentSessionEvent, ExtensionFactory } from "@earendil-works/pi-coding-agent";

/**
 * What the journal keeps of each session event. Native Pi JSONL owns message bodies and tool results;
 * the journal adds execution timing, retries and compaction, which are not reconstructed by guessing
 * from the saved messages. `type` keeps the event's name alone, since the full event can duplicate
 * entire conversations and native sessions retain that content; `none` keeps nothing.
 */
const JOURNAL = {
	tool_execution_start: "detail",
	tool_execution_end: "detail",
	message_end: "detail",
	summarization_retry_attempt_start: "type",
	summarization_retry_finished: "type",
	summarization_retry_scheduled: "type",
	agent_settled: "type",
	agent_start: "type",
	agent_end: "type",
	turn_start: "type",
	turn_end: "type",
	compaction_start: "type",
	compaction_end: "type",
	auto_retry_start: "type",
	auto_retry_end: "type",
	bash_execution_update: "none",
	entry_appended: "none",
	message_start: "none",
	message_update: "none",
	queue_update: "none",
	session_info_changed: "none",
	thinking_level_changed: "none",
	tool_execution_update: "none",
} satisfies Record<AgentSessionEvent["type"], "detail" | "type" | "none">;

// Read off the table, so a `detail` entry without a case in `eventDetail` fails to compile.
type DetailedEvent = Extract<
	AgentSessionEvent,
	{
		type: {
			[T in keyof typeof JOURNAL]: (typeof JOURNAL)[T] extends "detail" ? T : never;
		}[keyof typeof JOURNAL];
	}
>;

function isDetailed(event: AgentSessionEvent): event is DetailedEvent {
	return JOURNAL[event.type] === "detail";
}

function eventDetail(event: DetailedEvent): Record<string, unknown> {
	switch (event.type) {
		case "tool_execution_start": {
			return { type: event.type, toolCallId: event.toolCallId, toolName: event.toolName };
		}
		case "tool_execution_end": {
			return {
				type: event.type,
				toolCallId: event.toolCallId,
				toolName: event.toolName,
				isError: event.isError,
			};
		}
		case "message_end": {
			return {
				type: event.type,
				role: event.message.role,
				...(event.message.role === "assistant"
					? { stopReason: event.message.stopReason, usage: event.message.usage }
					: {}),
			};
		}
	}
}

/** Private execution artifacts, never application logs or telemetry attributes. */
export class ReviewTrace {
	readonly sessionDir: string;
	private readonly directory: string;
	private bytes = 0;
	private sessionBytes = 0;
	private sequence = 0;
	private dropped = 0;
	private requests = 0;
	private readonly payloads = new Set<string>();
	private readonly sessions = new Map<string, string | undefined>();
	private readonly activeSessions = new Set<string>();
	private readonly maxBytes: number;

	// Bound journal and native session copies together, leaving room for review results and tar headers.
	constructor(outputDirectory: string, maxBytes = 32 * 1024 * 1024) {
		if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) {
			throw new Error("Capture limit must be a positive integer");
		}
		this.maxBytes = maxBytes;
		this.directory = path.join(outputDirectory, "trace");
		// Pi owns the live files outside the collected output: an oversized native session must
		// never make the host reject result.json under its 10 MiB member / 50 MiB archive limits.
		this.sessionDir = path.join(outputDirectory, "..", ".sessions");
		mkdirSync(this.sessionDir, { recursive: true, mode: 0o700 });
		mkdirSync(path.join(this.directory, "sessions"), { recursive: true, mode: 0o700 });
		this.status(false);
	}

	/** Pi supplies the final provider-specific body; no headers, credentials or environment are captured. */
	readonly extension: ExtensionFactory = (pi) => {
		pi.on("before_provider_request", (event, context) => {
			const content = JSON.stringify(event.payload);
			const sha256 = createHash("sha256").update(content).digest("hex");
			const requestPath = `requests/${sha256}.json`;
			const bytes = Buffer.byteLength(content);
			let captured = this.payloads.has(sha256);
			if (!captured && this.reserve(bytes)) {
				try {
					mkdirSync(path.join(this.directory, "requests"), { recursive: true, mode: 0o700 });
					writeFileSync(path.join(this.directory, requestPath), content, {
						flag: "wx",
						mode: 0o600,
					});
					this.payloads.add(sha256);
					captured = true;
				} catch {
					this.dropped += 1;
				}
			}
			this.requests += 1;
			this.record(context.sessionManager.getSessionId(), {
				type: "provider_request",
				sha256,
				bytes,
				path: captured ? requestPath : null,
				captured,
			});
			// Returning nothing is essential: tracing must never rewrite the request it observes.
		});
		pi.on("after_provider_response", (event, context) => {
			this.record(context.sessionManager.getSessionId(), {
				type: "provider_response",
				status: event.status,
			});
		});
	};

	session(sessionId: string, label: string, sessionFile: string | undefined) {
		this.sessions.set(sessionId, sessionFile);
		this.record(sessionId, {
			type: "session",
			label,
			sessionFile: sessionFile === undefined ? null : `sessions/${path.basename(sessionFile)}`,
		});
	}

	event(sessionId: string, event: AgentSessionEvent) {
		if (event.type === "agent_start") {
			this.activeSessions.add(sessionId);
		}
		if (event.type === "agent_settled") {
			this.activeSessions.delete(sessionId);
		}
		if (isDetailed(event)) {
			this.record(sessionId, eventDetail(event));
		} else if (JOURNAL[event.type] === "type") {
			this.record(sessionId, { type: event.type });
		}
	}

	finish(exitCode: number) {
		// Sessions are settled before process exit. Copy whole native files or omit them; truncating
		// JSONL would destroy Pi's replay contract and must not masquerade as a complete session.
		for (const file of new Set(this.sessions.values())) {
			if (file === undefined) {
				continue;
			}
			try {
				const { size } = statSync(file);
				if (this.reserve(size)) {
					copyFileSync(file, path.join(this.directory, "sessions", path.basename(file)));
					this.sessionBytes += size;
				}
			} catch {
				this.dropped += 1;
			}
		}
		this.status(true, exitCode);
	}

	private reserve(bytes: number) {
		if (bytes > 8 * 1024 * 1024 || this.bytes + bytes > this.maxBytes) {
			this.dropped += 1;
			this.status(false);
			return false;
		}
		this.bytes += bytes;
		return true;
	}

	private record(sessionId: string, data: Record<string, unknown>) {
		this.sequence += 1;
		const record = {
			sequence: this.sequence,
			timestamp: new Date().toISOString(),
			sessionId,
			...data,
		};
		const line = `${JSON.stringify(record)}\n`;
		if (!this.reserve(Buffer.byteLength(line))) {
			return;
		}
		// Short fixed-width parts keep individual files and USTAR member names inside the output ABI.
		const part = Math.floor(this.sequence / 1000)
			.toString()
			.padStart(5, "0");
		try {
			appendFileSync(path.join(this.directory, `events-${part}.jsonl`), line, { mode: 0o600 });
		} catch {
			this.dropped += 1;
		}
	}

	private status(finished: boolean, exitCode?: number) {
		writeFileSync(
			path.join(this.directory, "capture.json"),
			`${JSON.stringify(
				{
					schemaVersion: 1,
					format: "pi-native-session-tree",
					finished,
					exitCode,
					complete:
						finished &&
						this.dropped === 0 &&
						this.activeSessions.size === 0 &&
						[...this.sessions.values()].every(
							(file) =>
								file !== undefined &&
								existsSync(path.join(this.directory, "sessions", path.basename(file))),
						),
					unfinishedSessions: this.activeSessions.size,
					journalBytes: this.bytes - this.sessionBytes,
					sessionBytes: this.sessionBytes,
					captureBytes: this.bytes,
					droppedRecords: this.dropped,
					providerRequests: this.requests,
					sessions: this.sessions.size,
					limits: { captureBytes: this.maxBytes, fileBytes: 8 * 1024 * 1024 },
					limitations: [
						"Native session JSONL is not a transport packet capture; provider-internal processing is not observable.",
						"Abrupt termination may leave the last session or request unfinished; the host execution state is authoritative.",
					],
				},
				null,
				2,
			)}\n`,
			{ mode: 0o600 },
		);
	}
}
