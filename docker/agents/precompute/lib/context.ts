/** Read-only helpers for a precompute script's task-declared context root. */

import { readFile } from "node:fs/promises";

import { isJsonObject } from "./practice-contract.ts";

/** Returns null for absent, unreadable or invalid JSON; callers validate the shape. */
export async function readContextJson(
	contextDir: string | undefined,
	name: string,
): Promise<unknown> {
	if (!contextDir) return null;
	try {
		return JSON.parse(await readFile(`${contextDir}/${name}`, "utf8"));
	} catch {
		return null;
	}
}

/** Shape of `project_inventory.json` (see WorkspaceInventoryContentSource). All fields best-effort. */
export interface ProjectInventory {
	repository?: string;
	focal?: { type?: string; number?: number };
	issues?: InventoryItem[];
	pullRequests?: InventoryItem[];
	counts?: { issuesListed?: number; pullRequestsListed?: number };
	truncated?: boolean;
}

export interface InventoryItem {
	number: number;
	title: string;
	state?: string;
	author?: string;
	milestone?: string;
	url?: string;
	isDraft?: boolean;
}

function optionalString(value: unknown): string | undefined {
	return typeof value === "string" ? value : undefined;
}

function optionalNumber(value: unknown): number | undefined {
	return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function optionalBoolean(value: unknown): boolean | undefined {
	return typeof value === "boolean" ? value : undefined;
}

function parseInventoryItems(value: unknown): InventoryItem[] | undefined {
	if (!Array.isArray(value)) return undefined;
	const items: InventoryItem[] = [];
	for (const entry of value) {
		if (!isJsonObject(entry)) continue;
		const number = optionalNumber(entry.number);
		const title = optionalString(entry.title);
		if (number === undefined || title === undefined) continue;
		items.push({
			number,
			title,
			state: optionalString(entry.state),
			author: optionalString(entry.author),
			milestone: optionalString(entry.milestone),
			url: optionalString(entry.url),
			isDraft: optionalBoolean(entry.isDraft),
		});
	}
	return items;
}

export function parseProjectInventory(value: unknown): ProjectInventory | null {
	if (!isJsonObject(value)) return null;
	const focal = isJsonObject(value.focal) ? value.focal : undefined;
	const counts = isJsonObject(value.counts) ? value.counts : undefined;
	return {
		repository: optionalString(value.repository),
		focal: focal && {
			type: optionalString(focal.type),
			number: optionalNumber(focal.number),
		},
		issues: parseInventoryItems(value.issues),
		pullRequests: parseInventoryItems(value.pullRequests),
		counts: counts && {
			issuesListed: optionalNumber(counts.issuesListed),
			pullRequestsListed: optionalNumber(counts.pullRequestsListed),
		},
		truncated: optionalBoolean(value.truncated),
	};
}

export async function readProjectInventory(
	contextDir: string | undefined,
): Promise<ProjectInventory | null> {
	return parseProjectInventory(await readContextJson(contextDir, "project_inventory.json"));
}
