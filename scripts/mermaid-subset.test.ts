import assert from "node:assert/strict";
import { test } from "node:test";

import { subsetProblems } from "./lib/mermaid-subset.ts";

await test("a flowchart, a sequence, a state machine and an ER diagram pass", () => {
	for (const source of [
		"flowchart LR\n    A -->|calls| B\n    classDef stop stroke:#d97706\n",
		"sequenceDiagram\n    A->>B: Request\n    B-->>A: Answer\n",
		"stateDiagram-v2\n    [*] --> QUEUED\n",
		"---\nconfig:\n    layout: elk\n---\nerDiagram\n    A ||--o{ B : has\n",
		"classDiagram\n    class Port {\n        <<interface>>\n    }\n",
	]) {
		assert.deepEqual(subsetProblems(source), [], source);
	}
});

await test("a diagram type that Mermaid 13 does not port fails", () => {
	for (const type of [
		"C4Context",
		"C4Container",
		"architecture-beta",
		"block-beta",
		"requirementDiagram",
	]) {
		assert.equal(subsetProblems(`%% a comment\n${type}\n    title x\n`).length, 1, type);
	}
});

await test("HTML labels, click callbacks, id CSS and dropped settings fail", () => {
	assert.equal(subsetProblems("flowchart LR\n    A[One<br/>two] --> B\n").length, 1);
	assert.equal(subsetProblems("flowchart LR\n    A --> B\n    click A callback\n").length, 1);
	assert.equal(subsetProblems("flowchart LR\n    A --> B\n    style A fill:#fff\n").length, 1);
	assert.equal(
		subsetProblems("%%{init: {'securityLevel': 'loose'}}%%\nflowchart LR\n    A --> B\n").length,
		1,
	);
});
