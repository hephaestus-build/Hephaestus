import { describe, expect, it } from "vitest";

import { TEAMMATE, YOU } from "./fixtures";
import { decisionsByProblem, type TeamDecisionData } from "./team-decisions";

function record(id: string, problemId: string, minute: number): TeamDecisionData {
	return {
		id,
		problemId,
		problemSummary: `Problem ${problemId}`,
		practiceName: "Research findings reach the design",
		versionId: "v1",
		versionSequence: 1,
		state: "OPEN",
		decidedBy: minute % 2 === 0 ? YOU : TEAMMATE,
		decidedAt: new Date(Date.UTC(2026, 11, 3, 10, minute)),
	};
}

describe("decisionsByProblem", () => {
	it("makes the newest record of each problem its current one, and keeps the older ones newest first", () => {
		const grouped = decisionsByProblem([
			record("a1", "p1", 1),
			record("a3", "p1", 3),
			record("a2", "p1", 2),
		]);
		expect(grouped).toHaveLength(1);
		expect(grouped[0]?.current.id).toBe("a3");
		expect(grouped[0]?.earlier.map((decision) => decision.id)).toStrictEqual(["a2", "a1"]);
	});

	it("orders problems by their current record, newest first", () => {
		const grouped = decisionsByProblem([
			record("a1", "p1", 5),
			record("b1", "p2", 1),
			record("b2", "p2", 9),
		]);
		expect(grouped.map((decisions) => decisions.current.id)).toStrictEqual(["b2", "a1"]);
	});

	it("returns no groups for no records", () => {
		expect(decisionsByProblem([])).toStrictEqual([]);
	});
});
