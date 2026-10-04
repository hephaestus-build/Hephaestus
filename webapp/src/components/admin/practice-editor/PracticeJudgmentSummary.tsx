import type { PracticeJudgment } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";

import { ruleCondition } from "./practice-judgment";

export interface PracticeJudgmentSummaryProps {
	/** Absent for a practice Hephaestus never reviews automatically. */
	judgment: PracticeJudgment | undefined;
}

/** The questions a review answers and the rules that decide, read top to bottom as the review applies them. */
export function PracticeJudgmentSummary({ judgment }: PracticeJudgmentSummaryProps) {
	if (judgment === undefined) {
		return (
			<p className="text-sm text-muted-foreground">
				This practice asks no questions: Hephaestus does not review it automatically.
			</p>
		);
	}
	return (
		<div className="max-w-2xl space-y-5 text-sm">
			<section className="space-y-2">
				<h4 className="font-medium">The reviewer answers</h4>
				<ol className="list-inside list-decimal space-y-2">
					{judgment.questions.map((question) => (
						<li key={question.key}>
							<span className="font-medium">{question.title}</span>
							<p className="text-pretty text-muted-foreground">{question.question}</p>
						</li>
					))}
				</ol>
			</section>
			<section className="space-y-2">
				<h4 className="font-medium">The first matching rule decides</h4>
				<ol className="space-y-2">
					{judgment.rules.map((rule) => (
						<li key={rule.id} className="space-y-1">
							<div className="flex flex-wrap items-center gap-2">
								<span>{ruleCondition(rule, judgment.questions)}</span>
								<StatusBadge def={OUTCOME_DEFS[rule.outcome]} />
								{rule.severity !== undefined && <StatusBadge def={SEVERITY_DEFS[rule.severity]} />}
							</div>
							<p className="text-pretty text-muted-foreground">{rule.reason}</p>
						</li>
					))}
				</ol>
			</section>
		</div>
	);
}
