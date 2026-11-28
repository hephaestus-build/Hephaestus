import assert from "node:assert/strict";
import { test } from "node:test";

import { type CapabilityNode, inspectNode, setReviewRelaunch } from "./node-capabilities.ts";

/** A node as Figma gives it for some FigJam tables and their cells: none of the relaunch or plugin-data methods. */
function bareNode(type: string): CapabilityNode {
	return { type };
}

/** A node that holds relaunch data and records every write. */
function capableNode(): CapabilityNode & { writes: Record<string, string>[] } {
	const writes: Record<string, string>[] = [];
	let data: Record<string, string> = {};
	return {
		type: "SECTION",
		writes,
		getRelaunchData: () => data,
		setRelaunchData: (next) => {
			writes.push(next);
			data = next;
		},
		getPluginDataKeys: () => [],
	};
}

await test("node capability guard: is needed: a table node can lack the method, so an unguarded call would throw", () => {
	// The fixture has no method, as Figma gave it in proof 1 of #5406: an unguarded call would throw a TypeError there.
	const method: unknown = Reflect.get(bareNode("TABLE"), "setRelaunchData");
	assert.equal(method, undefined);
});

await test("node capability guard: inspects a table and its cell without the methods, and reports instead of throwing", () => {
	for (const node of [bareNode("TABLE"), bareNode("TABLE_CELL")]) {
		assert.deepStrictEqual(inspectNode(node), {
			relaunchReadable: false,
			relaunchWritable: false,
			relaunchCommands: [],
			pluginDataKeys: [],
		});
	}
});

await test("node capability guard: writes nothing to a node without the method, and says to use Actions", () => {
	assert.equal(setReviewRelaunch(bareNode("TABLE_CELL"), true), "UNSUPPORTED");
});

await test("node capability guard: catches a refused write", () => {
	const node: CapabilityNode = {
		type: "TABLE",
		setRelaunchData: () => {
			throw new Error("refused");
		},
	};
	assert.equal(setReviewRelaunch(node, true), "REFUSED");
});

await test("node capability guard: stores only the review command, and removes it again", () => {
	const node = capableNode();
	assert.equal(setReviewRelaunch(node, true), "WRITTEN");
	assert.deepStrictEqual(inspectNode(node).relaunchCommands, ["review"]);
	assert.equal(setReviewRelaunch(node, false), "WRITTEN");
	assert.deepStrictEqual(node.writes, [{ review: "" }, {}]);
	assert.deepStrictEqual(inspectNode(node).relaunchCommands, []);
});
