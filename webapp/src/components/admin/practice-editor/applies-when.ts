import type { AppliesWhen, AppliesWhenClause, PracticeItemTypeOption } from "@/api/types.gen";
import { hasText } from "@/lib/text";

import { pathPatternProblem } from "./scan-settings";

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
