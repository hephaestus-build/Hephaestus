// tool-program.ts — a practice program's process on the tools side, as the program host sees it.

import { AsyncLocalStorage } from "node:async_hooks";

import type { ProgramProcess, ProgramStart } from "./work/practice-scripts/lib/core/script-run.ts";

import type { ToolClient } from "./tool-client.ts";

/**
 * Runs a program over `tools`. The host keeps every check: this only carries messages, and a message from the tools
 * side reaches the host as text that it parses and bounds as it does any other.
 */
export function toolsProgram(tools: ToolClient, start: ProgramStart): ProgramProcess {
	// Frames arrive on a socket that every program shares. A child process's events ran in the practice's own context,
	// and the host reads the practice's state from it, so each message goes back to the context that started it.
	const inPracticeContext = AsyncLocalStorage.snapshot();
	const listeners = {
		output: (_chunk: Buffer): void => undefined,
		message: (_raw: unknown): void => undefined,
		close: (_signal: string | null): void => undefined,
		error: (_failure: Error): void => undefined,
	};
	const channel = tools.script(
		start,
		(text) => {
			inPracticeContext(() => {
				listeners.message(text);
			});
		},
		(chunk) => {
			inPracticeContext(() => {
				listeners.output(chunk);
			});
		},
	);
	const watchExit = async () => {
		try {
			const { signal } = await channel.exit;
			inPracticeContext(() => {
				listeners.close(signal);
			});
		} catch (error) {
			inPracticeContext(() => {
				listeners.error(error instanceof Error ? error : new Error(String(error)));
				listeners.close(null);
			});
		}
	};
	void watchExit();
	return {
		get connected() {
			return channel.connected();
		},
		send: (message) => {
			channel.send(message);
		},
		kill: () => {
			channel.kill();
		},
		onOutput: (listener) => {
			listeners.output = listener;
		},
		onMessage: (listener) => {
			listeners.message = listener;
		},
		onClose: (listener) => {
			listeners.close = listener;
		},
		onError: (listener) => {
			listeners.error = listener;
		},
	};
}
