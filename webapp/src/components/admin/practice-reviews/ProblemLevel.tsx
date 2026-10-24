import { cn } from "cn";

import type { ReviewProblemDetail } from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { SectionStack } from "@/components/layout/SectionStack";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { DrawerBody } from "@/components/ui/drawer";
import { hasText } from "@/lib/text";

import { levelFoldKey } from "./level-folds";
import { LevelBodySkeleton } from "./LevelBodySkeleton";
import { SheetSection } from "./RecordSheet";
import { citationLocation, ResultEvidence } from "./ResultEvidence";
import { resultLevel } from "./review-levels";
import { ReviewFact, ReviewFactGrid } from "./ReviewFactGrid";
import { ReviewItemLink } from "./ReviewItem";
import { ReviewLevelHeader } from "./ReviewLevelHeader";

export interface ProblemLevelProps {
	nested?: boolean;
	path: LevelPath;
	problem: PanelState<{ problem: ReviewProblemDetail }>;
}

/**
 * One problem under a Not met result: what is wrong, where, how bad, the passages that show it and
 * the next step. A mark is an assessment of the whole result, so this sheet offers none: it links to
 * the result, whose sheet does.
 */
export function ProblemLevel({ nested, path, problem: state }: ProblemLevelProps) {
	const detail = state.status === "ready" ? state.problem : undefined;
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="problem"
			loading={state.status === "loading"}
			chips={detail && <StatusBadge def={SEVERITY_DEFS[detail.severity]} />}
			title={detail?.summary}
			description={detail && <ReviewItemLink item={detail.item} />}
		/>
	);
	if (state.status !== "ready") {
		return (
			<>
				{header}
				<DrawerBody className="pt-2">
					{state.status === "error" ? (
						<QueryErrorAlert
							error={state.error}
							title="We could not load this problem"
							onRetry={state.onRetry}
						/>
					) : (
						<LevelBodySkeleton />
					)}
				</DrawerBody>
			</>
		);
	}
	const { problem } = state.problem;
	const { sourceKind } = state.problem;
	const location =
		sourceKind === null ? undefined : citationLocation({ ...state.problem, sourceKind });
	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				<SectionStack>
					<SheetSection part="what" foldKey={levelFoldKey("problem", "what")}>
						<ReviewFactGrid>
							<ReviewFact label="Practice">
								<span className="break-words">{state.problem.practiceName}</span>
							</ReviewFact>
							{location !== undefined && (
								<ReviewFact label="Location">
									<span className="font-mono text-xs break-all">{location}</span>
								</ReviewFact>
							)}
							<ReviewFact label="Observation">
								{/* One record sheet stands at a time (`layeredStack`): its parent takes its place, and Back returns here. */}
								<DetailStackLink
									entry={resultLevel(state.problem.resultId)}
									single
									className="underline-offset-4 hover:underline"
								>
									{state.problem.result.summary ?? state.problem.result.practiceName}
								</DetailStackLink>
							</ReviewFact>
						</ReviewFactGrid>
					</SheetSection>
					{!state.problem.evidenceHidden && hasText(problem.explanation) && (
						<Section
							level={3}
							size="lg"
							title="Why this was raised"
							foldKey={levelFoldKey("problem", "why")}
						>
							{/* The review writes its explanation in Markdown. */}
							<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "text-sm")}>
								<UntrustedMarkdown>{problem.explanation}</UntrustedMarkdown>
							</div>
						</Section>
					)}
					{!state.problem.evidenceHidden && (
						<Section
							level={3}
							size="lg"
							title="Evidence"
							foldKey={levelFoldKey("problem", "evidence")}
						>
							<ResultEvidence citations={problem.citations} />
						</Section>
					)}
					{!state.problem.evidenceHidden && hasText(problem.nextStep) && (
						<Section
							level={3}
							size="lg"
							title="Next step"
							foldKey={levelFoldKey("problem", "next-step")}
						>
							<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "text-sm")}>
								<UntrustedMarkdown>{problem.nextStep}</UntrustedMarkdown>
							</div>
						</Section>
					)}
				</SectionStack>
				{state.problem.evidenceHidden && (
					<p className="text-sm text-muted-foreground">The evidence of this problem is hidden.</p>
				)}
				<p className="text-sm text-muted-foreground">
					A mark applies to the whole observation, not to one problem. Open the observation to mark
					it incorrect or accepted.
				</p>
			</DrawerBody>
		</>
	);
}
