import { Download, FlaskConical } from "lucide-react";
import { useId } from "react";

import type {
	Question,
	QuestionSummary,
	Survey,
	SurveyResponse,
	SurveySummary,
} from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { TablePagination } from "@/components/common/TablePagination";
import { DetailDrawerHeader } from "@/components/core/detail-drawer/DetailDrawerHeader";
import {
	SURVEY_AVAILABILITY_DEFS,
	surveyAvailability,
} from "@/components/feedback/survey-availability-defs";
import { SURVEY_PURPOSE_DEFS } from "@/components/feedback/survey-purpose-defs";
import { formatAnswer, NPS_LABELS } from "@/components/feedback/survey-questions";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

import { surveyAudience } from "./AdminSurveysTable";
import {
	completionRate,
	distributionRows,
	NPS_BUCKETS,
	npsBuckets,
	shareOf,
} from "./survey-summary";
import { SurveyActions } from "./SurveyActions";

export type AdminSurveyResultsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			survey: Survey;
			summary: SurveySummary;
			responses: SurveyResponse[];
			page: number;
			totalPages: number;
			onPageChange: (page: number) => void;
	  };

export interface AdminSurveyResultsProps {
	state: AdminSurveyResultsState;
	now: number;
	nested?: boolean;
	exporting: boolean;
	onExport: () => void;
	pending: boolean;
	onToggleActive: (survey: Survey, active: boolean) => void;
	onEnd: (survey: Survey) => void;
	onDelete: (survey: Survey) => void;
}

/**
 * A survey's results as a drawer level: who was asked, what they answered per question, and every
 * response in full. The distributions are lists of text first and bars second, so a reader without
 * the bars still gets the numbers.
 */
export function AdminSurveyResults({
	state,
	now,
	nested,
	exporting,
	onExport,
	pending,
	onToggleActive,
	onEnd,
	onDelete,
}: AdminSurveyResultsProps) {
	const id = useId();
	if (state.status !== "ready") {
		return (
			<>
				<DetailDrawerHeader nested={nested}>
					<div className="min-w-0 flex-1 space-y-0.5">
						<DrawerTitle>Survey results</DrawerTitle>
						<DrawerDescription>
							{state.status === "loading"
								? "Loading the survey and its responses."
								: "The survey could not be loaded."}
						</DrawerDescription>
					</div>
				</DetailDrawerHeader>
				<DrawerBody className="flex flex-col gap-8">
					{state.status === "loading" ? (
						<AdminSurveyResultsSkeleton />
					) : (
						<QueryErrorAlert
							error={state.error}
							title="Survey results couldn't be loaded"
							onRetry={state.onRetry}
						/>
					)}
				</DrawerBody>
			</>
		);
	}

	const { survey, summary, responses } = state;
	const availability = surveyAvailability(survey, now);
	const summaries = new Map(summary.questions.map((question) => [question.questionId, question]));
	const hasExport = survey.participation.responded + survey.participation.declined > 0;
	const stats = [
		{ label: "Invited", value: String(survey.participation.invited) },
		{ label: "Responded", value: String(survey.participation.responded) },
		{ label: "Declined", value: String(survey.participation.declined) },
		{ label: "Completion rate", value: completionRate(survey.participation) },
	];

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<div className="min-w-0 flex-1 space-y-2">
					<DrawerTitle className="break-words">{survey.title}</DrawerTitle>
					<div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
						<StatusBadge def={SURVEY_AVAILABILITY_DEFS[availability]} />
						{survey.purpose === "RESEARCH" && <StatusBadge def={SURVEY_PURPOSE_DEFS.RESEARCH} />}
						<span>{surveyAudience(survey)}</span>
					</div>
					<DrawerDescription>
						{availability === "SCHEDULED" ? "Starts " : "Started "}
						<RelativeTime value={survey.startsAt} />
						{survey.endsAt ? (
							<>
								{availability === "ENDED" ? " · Ended " : " · Ends "}
								<RelativeTime value={survey.endsAt} />
							</>
						) : (
							" · No end"
						)}
						{" · Published by "}
						{survey.createdBy?.displayName ?? "a deleted account"},{" "}
						<RelativeTime value={survey.createdAt} />
					</DrawerDescription>
					<div className="flex flex-wrap items-center gap-2">
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={!hasExport || exporting}
							onClick={onExport}
						>
							{exporting ? <Spinner className="size-4" /> : <Download aria-hidden />}
							{exporting ? "Exporting…" : "Export CSV"}
						</Button>
						<SurveyActions
							survey={survey}
							now={now}
							pending={pending}
							onToggleActive={onToggleActive}
							onEnd={onEnd}
							onDelete={onDelete}
						/>
					</div>
				</div>
			</DetailDrawerHeader>

			<DrawerBody className="flex flex-col gap-8">
				{survey.purpose === "RESEARCH" && (
					<Alert>
						<FlaskConical />
						<AlertTitle>Research data, not product feedback</AlertTitle>
						<AlertDescription>
							These answers belong to the study run by {survey.researchOrganization}, given under
							the consent members recorded for it. Handle them by that study's protocol.
						</AlertDescription>
					</Alert>
				)}
				<dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
					{stats.map((stat) => (
						<div key={stat.label} className="rounded-lg border p-3">
							<dt className="text-xs text-muted-foreground">{stat.label}</dt>
							<dd className="text-2xl font-semibold">{stat.value}</dd>
						</div>
					))}
				</dl>

				{survey.questions.map((question, index) => (
					<QuestionResults
						key={question.id}
						headingId={`${id}-question-${question.id}`}
						number={index + 1}
						question={question}
						summary={summaries.get(question.id)}
					/>
				))}

				<section className="flex flex-col gap-3" aria-labelledby={`${id}-responses`}>
					<h3 id={`${id}-responses`} className="font-semibold text-lg">
						Responses
					</h3>
					{responses.length === 0 ? (
						<p className="text-sm text-muted-foreground">No responses yet.</p>
					) : (
						<ul className="flex flex-col gap-3">
							{responses.map((response) => (
								<li key={response.id} className="rounded-lg border p-4">
									<ResponseCard response={response} questions={survey.questions} />
								</li>
							))}
						</ul>
					)}
					<TablePagination
						page={state.page}
						totalPages={state.totalPages}
						onPageChange={state.onPageChange}
					/>
				</section>
			</DrawerBody>
		</>
	);
}

interface QuestionResultsProps {
	headingId: string;
	number: number;
	question: Question;
	summary: QuestionSummary | undefined;
}

function QuestionResults({ headingId, number, question, summary }: QuestionResultsProps) {
	const answered = summary?.answered ?? 0;
	const counts = summary?.counts ?? [];
	return (
		<section className="flex flex-col gap-3" aria-labelledby={headingId}>
			<div className="space-y-0.5">
				<h3 id={headingId} className="font-medium break-words">
					{number}. {question.prompt}
				</h3>
				<p className="text-sm text-muted-foreground">
					{answered} answered
					{summary?.average !== undefined && <> · Average {summary.average.toFixed(1)}</>}
					{question.type === "NPS" && summary?.score !== undefined && <> · NPS {summary.score}</>}
				</p>
			</div>
			{question.type === "TEXT" ? (
				<p className="text-sm text-muted-foreground">
					{answered} {answered === 1 ? "answer" : "answers"} — read them in the responses below.
				</p>
			) : (
				<>
					<Distribution
						rows={counts}
						answered={answered}
						other={summary?.other}
						label={`Answers to question ${number}`}
					/>
					{question.type === "RATING" && question.lowLabel && question.highLabel && (
						<p className="flex justify-between gap-4 text-xs text-muted-foreground">
							<span>1 · {question.lowLabel}</span>
							<span>5 · {question.highLabel}</span>
						</p>
					)}
					{question.type === "NPS" && <NpsBreakdown counts={counts} />}
				</>
			)}
		</section>
	);
}

/**
 * The option counts, then — set apart, without a bar — how many respondents typed an answer of
 * their own. Every share, that one included, is of everyone who answered the question; the typed
 * answers themselves are read one by one in the responses below.
 */
function Distribution({
	rows,
	answered,
	other,
	label,
}: {
	rows: QuestionSummary["counts"];
	answered: number;
	other: number | undefined;
	label: string;
}) {
	return (
		<ul className="flex flex-col gap-1.5" aria-label={label}>
			{distributionRows(rows, answered).map((row) => (
				<li key={row.value} className="flex flex-col gap-1 text-sm">
					<span className="flex items-baseline justify-between gap-3">
						<span className="min-w-0 break-words">{row.value}</span>
						<span className="shrink-0 text-muted-foreground tabular-nums">
							{row.count} · {row.percent}
						</span>
					</span>
					<span aria-hidden className="block h-1.5 w-full rounded bg-muted">
						<span className="block h-full rounded bg-primary" style={{ width: `${row.width}%` }} />
					</span>
				</li>
			))}
			{other !== undefined && other > 0 && (
				<li className="flex items-baseline justify-between gap-3 text-sm text-muted-foreground">
					<span className="min-w-0 italic">Something else</span>
					<span className="shrink-0 tabular-nums">
						{other} · {shareOf(other, answered)}
					</span>
				</li>
			)}
		</ul>
	);
}

function NpsBreakdown({ counts }: { counts: QuestionSummary["counts"] }) {
	const totals = npsBuckets(counts);
	const parts = NPS_BUCKETS.map(({ bucket, noun, low, high }) => {
		const count = totals[bucket];
		return `${count} ${count === 1 ? noun[0] : noun[1]} (${low}–${high})`;
	});
	return (
		<p className="text-xs text-muted-foreground">
			{parts.join(" · ")} · scale labelled {NPS_LABELS.low} → {NPS_LABELS.high}
		</p>
	);
}

function ResponseCard({
	response,
	questions,
}: {
	response: SurveyResponse;
	questions: Question[];
}) {
	const prompts = new Map(questions.map((question) => [question.id, question.prompt]));
	return (
		<div className="flex flex-col gap-3">
			<div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
				<span className="min-w-0">
					<span className="font-medium" title={response.account?.email}>
						{response.account?.displayName ?? "Deleted account"}
					</span>
					{response.workspace && (
						<span className="text-muted-foreground"> · {response.workspace.displayName}</span>
					)}
				</span>
				<RelativeTime value={response.decidedAt} className="text-muted-foreground" />
			</div>
			{response.status === "DECLINED" ? (
				<p className="text-sm text-muted-foreground">Declined</p>
			) : (
				<dl className="flex flex-col gap-2 text-sm">
					{(response.answers ?? []).map((answer) => (
						<div key={answer.questionId}>
							<dt className="text-muted-foreground break-words">
								{prompts.get(answer.questionId) ?? answer.questionId}
							</dt>
							<dd className="break-words whitespace-pre-wrap">{formatAnswer(answer)}</dd>
						</div>
					))}
				</dl>
			)}
		</div>
	);
}

function AdminSurveyResultsSkeleton() {
	return (
		<>
			<div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
				{Array.from({ length: 4 }, (_, index) => (
					<div key={index} className="rounded-lg border p-3">
						<Skeleton className="h-3 w-16" />
						<Skeleton className="mt-2 h-7 w-10" />
					</div>
				))}
			</div>
			{Array.from({ length: 2 }, (_, section) => (
				<div key={section} className="flex flex-col gap-3">
					<Skeleton className="h-5 w-2/3" />
					{Array.from({ length: 3 }, (_bar, row) => (
						<div key={row} className="flex flex-col gap-1">
							<Skeleton className="h-4 w-1/3" />
							<Skeleton className="h-1.5 w-full" />
						</div>
					))}
				</div>
			))}
		</>
	);
}
