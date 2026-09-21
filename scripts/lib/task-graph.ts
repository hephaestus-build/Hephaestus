import path from "node:path";
import { pathToFileURL } from "node:url";

import { asRecord } from "./json.ts";

/** The task map of the root `vite.config.ts`, loaded rather than pattern-matched. */
export async function loadTasks(): Promise<Record<string, unknown>> {
	const file = path.resolve(import.meta.dirname, "..", "..", "vite.config.ts");
	const module: unknown = await import(pathToFileURL(file).href);
	const config = asRecord(asRecord(module, "vite.config.ts").default, "vite.config");
	return asRecord(asRecord(config.run, "vite.config#run").tasks, "vite.config#run.tasks");
}

/** The command lines of a task, whether it is a string, a list, or a task object. */
export function commandsOf(task: unknown): string[] {
	const command =
		typeof task === "object" && task !== null && "command" in task ? task.command : task;
	const lines = Array.isArray(command) ? command : [command];
	return lines.filter((line): line is string => typeof line === "string");
}
