import type { ReviewRef } from "@/api/types.gen";

import { feedbackOf, type LineageRecords, type LineageResult } from "./lineage-chain";

export type TraceNode =
	| { kind: "work" }
	| { kind: "review" }
	| { kind: "results" }
	| { kind: "end" }
	| { kind: "result"; id: string }
	| { kind: "feedback"; id: string };

export function traceStepKey(node: TraceNode): string {
	return "id" in node ? `${node.kind}:${node.id}` : node.kind;
}

export function sameTraceNode(a: TraceNode | undefined, b: TraceNode | undefined): boolean {
	if (a === undefined || b === undefined || a.kind !== b.kind) {
		return false;
	}
	return !("id" in a) || !("id" in b) || a.id === b.id;
}

export function chainReview(records: LineageRecords): ReviewRef | undefined {
	const { root, reviews } = records;
	const byId = (id: string | undefined) => reviews.find((review) => review.id === id);
	switch (root?.kind) {
		case "REVIEW": {
			return root.review ?? undefined;
		}
		case "RESULT": {
			return byId(root.result?.reviewId);
		}
		case "PROBLEM": {
			const problemId = root.problem?.id;
			return byId(
				records.results.find((result) => result.problems.some((entry) => entry.id === problemId))
					?.reviewId,
			);
		}
		case "FEEDBACK": {
			const basis = records.feedback.find((entry) => entry.id === root.feedback?.id)?.basis ?? [];
			return byId(records.results.find((result) => basis.includes(result.id))?.reviewId);
		}
		case "ITEM":
		case undefined: {
			return reviews.length === 1 ? reviews[0] : undefined;
		}
	}
}

export function ownResults(records: LineageRecords): LineageResult[] {
	const review = chainReview(records);
	return review === undefined
		? records.results
		: records.results.filter((result) => result.reviewId === review.id);
}

/** What a pinned node draws strong. Undefined draws everything strong. */
export interface Traced {
	results: ReadonlySet<string>;
	feedback: ReadonlySet<string>;
}

export function tracedBy(records: LineageRecords, node: TraceNode | undefined): Traced | undefined {
	switch (node?.kind) {
		case undefined:
		case "end":
		case "work": {
			return undefined;
		}
		case "review":
		case "results": {
			const results = new Set(ownResults(records).map((entry) => entry.id));
			return {
				results,
				feedback: new Set(
					records.feedback
						.filter((entry) => entry.basis.some((id) => results.has(id)))
						.map((entry) => entry.id),
				),
			};
		}
		case "result": {
			return {
				results: new Set([node.id]),
				feedback: new Set(feedbackOf(records.feedback, node.id).map((entry) => entry.id)),
			};
		}
		case "feedback": {
			const feedback = records.feedback.find((entry) => entry.id === node.id);
			return {
				results: new Set(feedback?.basis),
				feedback: new Set([node.id]),
			};
		}
	}
}
