/**
 * The public script API, as `@hephaestus/practice-scripts`. What this file exports is a promise that
 * holds for a major version; see the runner guide before adding to it. Everything else is private.
 */
import type { ScriptDefinition } from "./core/practice-script.ts";

export { listWithin } from "./core/bounded-text.ts";
export { UnratedError } from "./core/practice-script.ts";
export type {
	Citation,
	EvidenceLocation,
	Facts,
	Omission,
	Outcome,
	PreparedContext,
	Problem,
	ReviewLead,
	ReviewOptions,
	ScriptContext as PracticeScriptContext,
	ScriptDefinition as PracticeScriptDefinition,
	Severity,
	UnratedReason,
} from "./core/practice-script.ts";

/** The script of a practice: the default export of its file. */
export const definePracticeScript = (definition: ScriptDefinition): ScriptDefinition => definition;
