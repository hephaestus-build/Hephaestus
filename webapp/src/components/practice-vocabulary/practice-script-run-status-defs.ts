import type { PracticeScriptRun } from "@/api/types.gen";

export type PracticeScriptRunStatus = PracticeScriptRun["status"];
export type PracticeScriptStopReason = NonNullable<PracticeScriptRun["stopReason"]>;
export type PracticeScriptNoResultCause = NonNullable<PracticeScriptRun["noResultCause"]>;

const scriptName = (run: Pick<PracticeScriptRun, "scriptKind">) =>
	run.scriptKind === "BUILTIN" ? "Built-in review" : "Practice script";

export const PRACTICE_SCRIPT_STOP_REASON_DEFS = {
	UNAVAILABLE: { sentence: "a model it needed was unavailable", result: "Model unavailable" },
	BUDGET: { sentence: "it used up its budget", result: "Out of budget" },
	DEADLINE: { sentence: "it ran out of time", result: "Ran out of time" },
	TOO_LARGE: { sentence: "a call was too large", result: "Call too large" },
	OFF_FORMAT: { sentence: "a model answered in the wrong format", result: "Wrong format" },
	REFUSED: { sentence: "a model refused to answer", result: "Model refused" },
	ERROR: { sentence: "it raised an error", result: "Error" },
	CANCELLED: { sentence: "the review was cancelled", result: "Cancelled" },
	INVALID_SCRIPT: {
		sentence: "it is not a script the review can run",
		result: "Not a runnable script",
	},
	INVALID_CANDIDATE: {
		sentence: "what it returned is not a candidate observation",
		result: "Returned no candidate observation",
	},
} satisfies Record<PracticeScriptStopReason, { sentence: string; result: string }>;

/** What the server proved about a review session that ended without a candidate observation; it says more than the stop reason. */
export const PRACTICE_SCRIPT_NO_RESULT_CAUSE_DEFS = {
	EVIDENCE_NOT_READ: {
		sentence:
			"its candidate observation was refused because it had not read the evidence it needed",
		result: "Evidence not read",
	},
	SESSION_ENDED: {
		sentence: "its review session ended without an accepted candidate observation",
		result: "No accepted candidate observation",
	},
} satisfies Record<PracticeScriptNoResultCause, { sentence: string; result: string }>;

function failedReason(run: PracticeScriptRun): { sentence: string; result: string } | undefined {
	if (run.noResultCause !== null) {
		return PRACTICE_SCRIPT_NO_RESULT_CAUSE_DEFS[run.noResultCause];
	}
	return run.stopReason === null ? undefined : PRACTICE_SCRIPT_STOP_REASON_DEFS[run.stopReason];
}

interface PracticeScriptRunStatusDef {
	/** The first sentence of the trace line under "What it saw". */
	sentence: (run: PracticeScriptRun) => string;
	/** The *Run* cell of the review's *Practice scripts* section. */
	result: (run: PracticeScriptRun) => string;
}

// Script execution is an operating fact; a status badge would imply a practice outcome.
export const PRACTICE_SCRIPT_RUN_STATUS_DEFS = {
	OK: {
		sentence: (run) => `${scriptName(run)} returned a candidate observation.`,
		result: () => "Returned a candidate observation",
	},
	WITHHELD: {
		sentence: (run) => `${scriptName(run)} did not run: the practice was withheld.`,
		result: () => "Withheld",
	},
	FAILED: {
		sentence: (run) => {
			const reason = failedReason(run);
			return reason === undefined
				? `${scriptName(run)} stopped without a candidate observation.`
				: `${scriptName(run)} stopped without a candidate observation: ${reason.sentence}.`;
		},
		result: (run) => {
			const reason = failedReason(run);
			return reason === undefined ? "Failed" : `Failed: ${reason.result.toLowerCase()}`;
		},
	},
	NOT_REACHED: {
		sentence: (run) => `The review ended before the ${scriptName(run).toLowerCase()} started.`,
		result: () => "Not reached",
	},
	NOT_FINISHED: {
		sentence: (run) => `${scriptName(run)} did not finish.`,
		result: () => "Did not finish",
	},
} satisfies Record<PracticeScriptRunStatus, PracticeScriptRunStatusDef>;

export function practiceScriptRunSentence(run: PracticeScriptRun): string {
	return PRACTICE_SCRIPT_RUN_STATUS_DEFS[run.status].sentence(run);
}

const OPERATIONS_GUIDE_URL = "https://docs.hephaestus.build/admin/practice-review-operations";

interface PracticeScriptGuideDef {
	/** The link's words: what the section answers. */
	label: string;
	/** The section of the operations guide. */
	anchor: string;
}

export const PRACTICE_SCRIPT_GUIDE_DEFS = {
	/** Why a program ran out of time or budget, or did not finish, and what to raise or speed up. */
	STOPPED_EARLY: { label: "Why scripts stop early", anchor: "the-trace-line" },
} satisfies Record<string, PracticeScriptGuideDef>;

export type PracticeScriptGuide = keyof typeof PRACTICE_SCRIPT_GUIDE_DEFS;

export function practiceScriptGuideUrl(guide: PracticeScriptGuide): string {
	return `${OPERATIONS_GUIDE_URL}#${PRACTICE_SCRIPT_GUIDE_DEFS[guide].anchor}`;
}

export type PracticeScriptFix = { kind: "EDIT" } | { kind: "GUIDE"; guide: PracticeScriptGuide };

export function practiceScriptFix(run: PracticeScriptRun): PracticeScriptFix | undefined {
	switch (run.status) {
		case "FAILED": {
			return failedFix(run);
		}
		case "NOT_REACHED":
		case "NOT_FINISHED": {
			return { kind: "GUIDE", guide: "STOPPED_EARLY" };
		}
		case "OK":
		case "WITHHELD": {
			return undefined;
		}
	}
}

// Time and budget stops link to operating limits; cancellation needs no script fix.
function failedFix(run: PracticeScriptRun): PracticeScriptFix | undefined {
	if (run.stopReason === "DEADLINE" || run.stopReason === "BUDGET") {
		return { kind: "GUIDE", guide: "STOPPED_EARLY" };
	}
	if (run.stopReason === "CANCELLED" || run.scriptKind === "BUILTIN") {
		return undefined;
	}
	return { kind: "EDIT" };
}
