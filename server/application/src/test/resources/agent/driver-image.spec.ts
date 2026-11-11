import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const agentSources = new URL("../../../main/resources/agent/", import.meta.url);
const dockerfile = new URL("../../../../../../docker/agents/pi/Dockerfile", import.meta.url);

/** The agent sources that the image copies from the agent-runtime context, over every continued line. */
function copiedFromAgentRuntime(): Set<string> {
	const joined = readFileSync(dockerfile, "utf8").replaceAll(/\\\n\s*/gu, " ");
	const copied = new Set<string>();
	for (const line of joined.split("\n")) {
		if (!line.startsWith("COPY --from=agent-runtime ")) {
			continue;
		}
		for (const part of line.split(/\s+/u).slice(2, -1)) {
			copied.add(part);
		}
	}
	return copied;
}

/** The local modules that `driver.ts` loads, directly or through another local module. */
function driverImports(): Set<string> {
	const loaded = new Set<string>();
	const pending = ["driver.ts"];
	for (let next = pending.pop(); next !== undefined; next = pending.pop()) {
		if (loaded.has(next)) {
			continue;
		}
		loaded.add(next);
		const source = readFileSync(new URL(next, agentSources), "utf8");
		for (const match of source.matchAll(/(?:from|import\()\s*["']\.\/(?<module>[^"']+)["']/gu)) {
			const module = match.groups?.module;
			if (module !== undefined) {
				pending.push(module);
			}
		}
	}
	return loaded;
}

void test("the agent image copies every module that a sandbox's driver loads", () => {
	const copied = copiedFromAgentRuntime();
	const missing = [...driverImports()].filter((module) => !copied.has(module));

	assert.deepEqual(missing, []);
});
