import type { LlmModelCapabilities } from "@/api/types.gen";
import { problemDetailOf } from "@/lib/problem-detail";

export type ToolChoiceTestState =
	| { status: "idle" }
	| { status: "testing" }
	| { status: "failed"; message: string };

/** A probe for another model does not belong to the open form. */
export function toolChoiceTestState(
	modelId: number,
	probe: {
		status: "idle" | "pending" | "error" | "success";
		variables?: { path: { id: number } };
		data?: LlmModelCapabilities;
		error: unknown;
	},
): ToolChoiceTestState {
	if (probe.variables?.path.id !== modelId) {
		return { status: "idle" };
	}
	switch (probe.status) {
		case "pending": {
			return { status: "testing" };
		}
		case "error": {
			return {
				status: "failed",
				message: problemDetailOf(
					probe.error,
					"The provider did not answer. The previous result is kept.",
				),
			};
		}
		case "success": {
			if (probe.data?.forcedToolChoice === undefined) {
				return {
					status: "failed",
					message: "The test was inconclusive. The previous result is kept.",
				};
			}
			return { status: "idle" };
		}
		case "idle": {
			return { status: "idle" };
		}
	}
}
