import { ListChecksIcon } from "lucide-react";
import type { ReactElement } from "react";

import { EmptyState } from "@/components/common/EmptyState";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Skeleton } from "@/components/ui/skeleton";

import { decisionsByProblem, type TeamDecisionData } from "./team-decisions";
import { TeamDecisionRecord } from "./TeamDecisionRecord";

export type TeamDecisionListState =
	| { status: "loading"; rows: number }
	| { status: "error"; error: unknown; onRetry?: () => void }
	| { status: "ready"; records: readonly TeamDecisionData[] };

export interface TeamDecisionListProps {
	state: TeamDecisionListState;
	/** Opens a decision's entry in its thread. */
	questionLink?: (decision: TeamDecisionData) => ReactElement;
}

/**
 * The team's current decisions, one per problem, with the records each superseded. Every current member reads them,
 * whatever their join date; the list says so before anyone records one.
 */
export function TeamDecisionList({ state, questionLink }: TeamDecisionListProps) {
	return (
		<section aria-labelledby="team-decisions-heading" className="flex flex-col gap-3">
			<div className="flex flex-col gap-0.5">
				<h2 id="team-decisions-heading" className="text-base font-semibold">
					Team decisions
				</h2>
				<p className="text-sm text-muted-foreground">
					Every current member of the team sees these records and who recorded them.
				</p>
			</div>
			<DecisionListBody state={state} questionLink={questionLink} />
		</section>
	);
}

function DecisionListBody({ state, questionLink }: TeamDecisionListProps) {
	switch (state.status) {
		case "loading": {
			return (
				<ul aria-hidden className="flex flex-col gap-3">
					{Array.from({ length: state.rows }, (_, index) => (
						<li key={index}>
							<Skeleton className="h-28 w-full" />
						</li>
					))}
				</ul>
			);
		}
		case "error": {
			return (
				<QueryErrorAlert
					error={state.error}
					title="Team decisions could not load"
					onRetry={state.onRetry}
				/>
			);
		}
		case "ready": {
			const problems = decisionsByProblem(state.records);
			if (problems.length === 0) {
				return (
					<EmptyState
						icon={<ListChecksIcon />}
						title="No team decisions yet"
						headingLevel={3}
						description="A member records a decision on a problem from a review. Hephaestus can propose one, but only a member records it."
					/>
				);
			}
			return (
				<ul className="flex flex-col gap-3">
					{problems.map((decisions) => (
						<li key={decisions.current.problemId}>
							<TeamDecisionRecord
								decisions={decisions}
								questionLink={questionLink?.(decisions.current)}
							/>
						</li>
					))}
				</ul>
			);
		}
	}
}
