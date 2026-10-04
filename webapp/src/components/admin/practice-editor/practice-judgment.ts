import type { PracticeJudgment, PracticeQuestion, PracticeRule } from "@/api/types.gen";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { QUESTION_ANSWER_DEFS } from "@/components/practice-vocabulary/question-answer-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";

/**
 * The server's limits on a judgment (`PracticeJudgment`, `PracticeQuestion`, `PracticeRule`). The server
 * refuses anything past them; the editor uses them to stop the author before a save does.
 */
export const MAX_QUESTIONS = 8;
export const MAX_RULES = 32;
export const MAX_TITLE_LENGTH = 60;
export const MAX_QUESTION_LENGTH = 2400;
export const MAX_MEANING_LENGTH = 600;
export const MIN_REASON_LENGTH = 10;
export const MAX_REASON_LENGTH = 200;
const KEY_PATTERN = /^[a-z][a-z0-9_]{1,39}$/u;
const RULE_ID_PATTERN = /^[a-z][a-z0-9-]{1,39}$/u;
/** The server's `PracticeDefinitionValidator` refuses these words anywhere an author writes for people. */
const RESULT_LABEL =
	/\b(?:PRESENT|ABSENT|GOOD|BAD|POSITIVE|NEGATIVE|ASSESSED|MET|NOT_MET|NOT_APPLICABLE|UNDETERMINED)\b/u;

function resultLabelProblem(field: string, ...texts: string[]): string | undefined {
	for (const text of texts) {
		const label = RESULT_LABEL.exec(text)?.[0];
		if (label !== undefined) {
			return `${field} names the review result “${label}”. The rules decide the result; say what the evidence shows in plain words.`;
		}
	}
	return undefined;
}

type Definite = "YES" | "NO";

/** Every combination of definite answers to these questions. */
function combinations(keys: readonly string[]): Record<string, Definite>[] {
	let all: Record<string, Definite>[] = [{}];
	for (const key of keys) {
		all = all.flatMap((answers) => [
			{ ...answers, [key]: "YES" },
			{ ...answers, [key]: "NO" },
		]);
	}
	return all;
}

function matches(rule: PracticeRule, answers: Record<string, Definite>): boolean {
	return Object.entries(rule.when).every(([key, answer]) => answers[key] === answer);
}

/**
 * What stops the server from accepting this judgment, worded as the server words it so an author reads
 * the same sentence before and after a save. Empty when it is valid.
 */
export function judgmentProblems(judgment: PracticeJudgment): string[] {
	const { questions, rules } = judgment;
	const problems: string[] = [];
	if (questions.length === 0) {
		problems.push("Add at least one question.");
	}
	if (questions.length > MAX_QUESTIONS) {
		problems.push(`A practice may ask at most ${MAX_QUESTIONS} questions.`);
	}
	const keys = new Set<string>();
	for (const [index, question] of questions.entries()) {
		const name = `Question ${index + 1}`;
		if (!KEY_PATTERN.test(question.key)) {
			problems.push(`${name} needs a title that starts with a letter.`);
		} else if (keys.has(question.key)) {
			problems.push(`${name} has the same title as an earlier question.`);
		}
		keys.add(question.key);
		if (question.title.trim().length === 0) {
			problems.push(`${name} needs a title.`);
		} else if (question.title.trim().endsWith(".")) {
			problems.push(`${name}: write the title as a statement without a final period.`);
		}
		if (question.question.trim().length === 0) {
			problems.push(`${name} needs the question text.`);
		}
		if (question.yes.trim().length === 0) {
			problems.push(`${name} needs to say what a yes means.`);
		}
		if (question.no.trim().length === 0) {
			problems.push(`${name} needs to say what a no means.`);
		}
		const label = resultLabelProblem(
			name,
			question.title,
			question.question,
			question.yes,
			question.no,
		);
		if (label !== undefined) {
			problems.push(label);
		}
	}
	if (rules.length < 2 || rules.length > MAX_RULES) {
		problems.push(`A practice needs 2–${MAX_RULES} rules.`);
	}
	const ids = new Set<string>();
	for (const [index, rule] of rules.entries()) {
		const name = `Rule ${index + 1}`;
		if (ids.has(rule.id)) {
			problems.push(`${name} has the same id as an earlier rule.`);
		}
		ids.add(rule.id);
		if ((rule.outcome === "NOT_MET") !== (rule.severity !== undefined)) {
			problems.push(
				rule.outcome === "NOT_MET"
					? `${name} decides Not met and needs a severity.`
					: `${name} gives a severity, but only a Not met rule has one.`,
			);
		}
		const reason = rule.reason.trim().length;
		if (reason < MIN_REASON_LENGTH || reason > MAX_REASON_LENGTH) {
			problems.push(
				`${name} needs a reason of ${MIN_REASON_LENGTH}–${MAX_REASON_LENGTH} characters: one sentence about the work.`,
			);
		}
		const label = resultLabelProblem(`The reason of ${name.toLowerCase()}`, rule.reason);
		if (label !== undefined) {
			problems.push(label);
		}
		if (Object.keys(rule.when).some((key) => !keys.has(key))) {
			problems.push(`${name} refers to a question this practice no longer asks.`);
		}
	}
	const last = rules.at(-1);
	if (last !== undefined && Object.keys(last.when).length > 0) {
		problems.push(
			"The last rule must have no conditions, so every combination of answers has an outcome.",
		);
	}
	if (problems.length > 0) {
		return problems;
	}
	const reached = new Set<number>();
	for (const answers of combinations([...keys])) {
		reached.add(rules.findIndex((rule) => matches(rule, answers)));
	}
	for (const index of rules.keys()) {
		if (!reached.has(index)) {
			problems.push(
				`Rule ${index + 1} can never decide: an earlier rule always matches first. Remove it or move it up.`,
			);
		}
	}
	for (const [index, question] of questions.entries()) {
		if (!rules.some((rule) => question.key in rule.when)) {
			problems.push(
				`Question ${index + 1} is not used by any rule. Use it in a rule or remove it.`,
			);
		}
	}
	return problems;
}

/** The key a question gets from its title: what the reviewer answers under, so it reads as words. */
export function questionKeyOf(title: string, taken: ReadonlySet<string>): string {
	const base =
		title
			.toLowerCase()
			.normalize("NFKD")
			.replaceAll(/[^a-z0-9]+/gu, "_")
			.replaceAll(/^_+|_+$/gu, "")
			.replace(/^[^a-z]+/u, "")
			.slice(0, 36)
			.replace(/_+$/u, "") || "question";
	const key = base.length < 2 ? `${base}_q` : base;
	let candidate = key;
	for (let suffix = 2; taken.has(candidate); suffix += 1) {
		candidate = `${key}_${suffix}`;
	}
	return candidate;
}

/** A free rule id; recorded on every observation the rule decides, so it never changes once saved. */
export function ruleIdOf(taken: ReadonlySet<string>): string {
	let suffix = taken.size + 1;
	while (taken.has(`rule-${suffix}`)) {
		suffix += 1;
	}
	return `rule-${suffix}`;
}

export function isValidRuleId(id: string): boolean {
	return RULE_ID_PATTERN.test(id);
}

/** "When “Says why” is no and “Has tests” is yes", or "In every other case" for the catch-all. */
export function ruleCondition(
	rule: Pick<PracticeRule, "when">,
	questions: readonly PracticeQuestion[],
): string {
	const conditions = Object.entries(rule.when).map(([key, answer]) => {
		const title = questions.find((question) => question.key === key)?.title ?? key;
		return `“${title}” is ${QUESTION_ANSWER_DEFS[answer].label.toLowerCase()}`;
	});
	return conditions.length === 0 ? "In every other case" : `When ${conditions.join(" and ")}`;
}

/** "Not met, Major", "Met". */
export function ruleResult(rule: Pick<PracticeRule, "outcome" | "severity">): string {
	const outcome = OUTCOME_DEFS[rule.outcome].label;
	return rule.severity === undefined
		? outcome
		: `${outcome}, ${SEVERITY_DEFS[rule.severity].label}`;
}

/** The judgment as plain text, for the places that compare definitions as text. */
export function judgmentText(judgment: PracticeJudgment): string {
	const questions = judgment.questions.map(
		(question, index) => `${index + 1}. ${question.title} — ${question.question}`,
	);
	const rules = judgment.rules.map(
		(rule) => `${ruleCondition(rule, judgment.questions)}: ${ruleResult(rule)}. ${rule.reason}`,
	);
	return ["Questions", ...questions, "", "Rules, first match decides", ...rules].join("\n");
}
