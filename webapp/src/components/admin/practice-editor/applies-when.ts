import type { AppliesWhen, AppliesWhenClause, PracticeItemTypeOption } from "@/api/types.gen";
import { hasText } from "@/lib/text";

const MAX_SCAN_PATTERN_LENGTH = 512;

/** Match server path rules: relative paths, forward slashes, and no traversal, .git, NUL, or brace expansion. */
export function pathPatternProblem(pattern: string): string | undefined {
	if (pattern.length > MAX_SCAN_PATTERN_LENGTH) {
		return `Use at most ${MAX_SCAN_PATTERN_LENGTH} characters in a pattern.`;
	}
	if (pattern.startsWith("/") || /^[A-Za-z]:/u.test(pattern)) {
		return `Use a path relative to the repository root, not “${pattern}”.`;
	}
	if (pattern.includes("\\")) {
		return `Use forward slashes in “${pattern}”.`;
	}
	if (pattern.includes("\0") || pattern.includes("{") || pattern.includes("}")) {
		return `Remove the brace expansion or control character from “${pattern}”.`;
	}
	const components = pattern.split("/");
	if (components.some((component) => component === "" || component === ".")) {
		return `Remove the empty or “.” segment from “${pattern}”.`;
	}
	if (components.includes("..")) {
		return `A pattern cannot leave the repository: remove “..” from “${pattern}”.`;
	}
	if (components.includes(".git")) {
		return `A scan never reads the .git folder: remove it from “${pattern}”.`;
	}
	return undefined;
}

export type ApplicabilityClause = PracticeItemTypeOption["applicabilityClauses"][number];

export function clauseKind(clause: AppliesWhenClause): ApplicabilityClause {
	if (clause.changedPath !== undefined) {
		return "CHANGED_PATH";
	}
	if (clause.merged === true) {
		return "MERGED";
	}
	return clause.diffContains === undefined ? "HAS_ITEMS" : "DIFF_CONTAINS";
}

export interface AppliesWhenDraft {
	clauses: AppliesWhenClause[];
	otherwise: string;
}

export function appliesWhenDraftOf(appliesWhen: AppliesWhen | null): AppliesWhenDraft {
	return { clauses: appliesWhen?.anyOf ?? [], otherwise: appliesWhen?.otherwise ?? "" };
}

export interface ParsedAppliesWhen {
	value: AppliesWhen | null;
	clausesError?: string;
	otherwiseError?: string;
}

export function parseAppliesWhen(
	draft: AppliesWhenDraft,
	itemType: PracticeItemTypeOption | undefined,
): ParsedAppliesWhen {
	if (draft.clauses.length === 0) {
		return { value: null };
	}
	const anyOf: AppliesWhenClause[] = [];
	for (const clause of draft.clauses) {
		if (itemType && !itemType.applicabilityClauses.includes(clauseKind(clause))) {
			return {
				value: null,
				clausesError: "This kind of work cannot check one of these conditions.",
			};
		}
		if (clause.merged === true) {
			anyOf.push({ merged: true });
		} else if (clause.changedPath !== undefined || clause.diffContains !== undefined) {
			const values = clause.changedPath ?? clause.diffContains ?? [];
			if (values.length === 0 || values.some((value) => !hasText(value.trim()))) {
				return { value: null, clausesError: "Enter a value for each condition." };
			}
			if (clause.changedPath === undefined) {
				anyOf.push({ diffContains: values });
			} else {
				const problem = values.map(pathPatternProblem).find((value) => value !== undefined);
				if (problem !== undefined) {
					return { value: null, clausesError: problem };
				}
				anyOf.push({ changedPath: values });
			}
		} else {
			const source = clause.hasItems;
			if (source === undefined || !hasText(source.trim())) {
				return { value: null, clausesError: "Choose a source." };
			}
			anyOf.push({ hasItems: source });
		}
	}
	const otherwise = draft.otherwise.trim();
	if (!hasText(otherwise)) {
		return {
			value: null,
			otherwiseError: "Say why the practice does not apply when no condition holds.",
		};
	}
	return { value: { anyOf, otherwise } };
}
