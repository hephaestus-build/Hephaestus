/** Direct model calls from a script. */
export {
	agent,
	classify,
	definePracticeScript,
	cosineSimilarity,
	embedMany,
	generateText,
	reasonOf,
	Output,
	rerank,
	step,
	z,
} from "./core/practice-script.ts";
export { MODEL_SLOT_CAPS } from "./core/contract.ts";
export type {
	ModelNeeds,
	PracticeScriptContext,
	QuestionSet,
	Rating,
} from "./core/practice-script.ts";
