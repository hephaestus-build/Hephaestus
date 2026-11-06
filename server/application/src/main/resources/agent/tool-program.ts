// tool-program.ts — a practice program's process on the tools side, as the program host sees it.

import type { ProgramProcess, ProgramStart } from "./work/practice-scripts/lib/core/script-run.ts";

import type { ToolClient } from "./tool-client.ts";

/**
 * Runs a program over `tools`. The host keeps every check: this only carries messages, and a message from the tools
 * side reaches the host as text that it parses and bounds as it does any other.
 */
export function toolsProgram(tools: ToolClient, start: ProgramStart): ProgramProcess {
	const listeners = {
		output: (_chunk: Buffer): void => undefined,
		message: (_raw: unknown): void => undefined,
		close: (_signal: string | null): void => undefined,
		error: (_failure: Error): void => undefined,
	};
	const channel = tools.script(
		start,
		(text) => {
			listeners.message(text);
		},
		(chunk) => {
			listeners.output(chunk);
		},
	);
	const watchExit = async () => {
		try {
			const { signal } = await channel.exit;
			listeners.close(signal);
		} catch (error) {
			listeners.error(error instanceof Error ? error : new Error(String(error)));
			listeners.close(null);
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
