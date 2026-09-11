import { BarChart3, ClipboardList } from "lucide-react";

import type { Survey } from "@/api/types.gen";
import { TableRowsSkeleton } from "@/components/admin/integrations/TableRowsSkeleton";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { TablePagination } from "@/components/common/TablePagination";
import { DetailStackLink } from "@/components/core/detail-drawer/DetailStackLink";
import {
	SURVEY_AVAILABILITY_DEFS,
	surveyAvailability,
} from "@/components/feedback/survey-availability-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { DropdownMenuItem } from "@/components/ui/dropdown-menu";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

import { surveyLevel } from "./admin-surveys-search";
import { percentOf } from "./survey-summary";
import { SurveyActions } from "./SurveyActions";

export type AdminSurveysTableState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			surveys: Survey[];
			page: number;
			totalPages: number;
			onPageChange: (page: number) => void;
	  };

export interface AdminSurveysTableProps {
	state: AdminSurveysTableState;
	/** The page clock; availability is derived from it at render time. */
	now: number;
	/** Surveys with a change in flight, whose menus wait. */
	pendingIds: ReadonlySet<string>;
	onToggleActive: (survey: Survey, active: boolean) => void;
	onEnd: (survey: Survey) => void;
	onDelete: (survey: Survey) => void;
}

const SKELETON_COLUMNS = ["w-48", "w-20", "w-32", "w-24", null];

export function surveyAudience(survey: Pick<Survey, "workspace">): string {
	return survey.workspace?.displayName ?? "All workspaces";
}

export function AdminSurveysTable({
	state,
	now,
	pendingIds,
	onToggleActive,
	onEnd,
	onDelete,
}: AdminSurveysTableProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Surveys couldn't be loaded"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "ready" && state.surveys.length === 0) {
		return (
			<Empty className="rounded-md border">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ClipboardList />
					</EmptyMedia>
					<EmptyTitle>No surveys yet</EmptyTitle>
					<EmptyDescription>
						Publish a short survey to ask members what would make Hephaestus more useful.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<div className="space-y-4">
			<div className="rounded-md border">
				<Table aria-label="Surveys" aria-busy={state.status === "loading"}>
					<TableHeader>
						<TableRow>
							<TableHead scope="col">Title</TableHead>
							<TableHead scope="col">Status</TableHead>
							<TableHead scope="col">Schedule</TableHead>
							<TableHead scope="col">Responses</TableHead>
							<TableHead scope="col" className="text-right">
								<span className="sr-only">Actions</span>
							</TableHead>
						</TableRow>
					</TableHeader>
					{state.status === "loading" ? (
						<TableRowsSkeleton columns={SKELETON_COLUMNS} rows={3} />
					) : (
						<TableBody>
							{state.surveys.map((survey) => {
								const availability = surveyAvailability(survey, now);
								const { invited, responded } = survey.participation;
								const completion = percentOf(responded, invited);
								return (
									<TableRow key={survey.id}>
										<TableCell className="max-w-xs">
											<DetailStackLink
												entry={surveyLevel(survey.id)}
												className="block truncate font-medium hover:underline"
											>
												{survey.title}
											</DetailStackLink>
											<span className="block truncate text-xs text-muted-foreground">
												{surveyAudience(survey)}
											</span>
										</TableCell>
										<TableCell>
											<StatusBadge def={SURVEY_AVAILABILITY_DEFS[availability]} />
										</TableCell>
										<TableCell className="text-sm text-muted-foreground">
											<span className="block">
												{availability === "SCHEDULED" ? "Starts " : "Started "}
												<RelativeTime value={survey.startsAt} />
											</span>
											<span className="block">
												{survey.endsAt ? (
													<>
														{availability === "ENDED" ? "Ended " : "Ends "}
														<RelativeTime value={survey.endsAt} />
													</>
												) : (
													"No end"
												)}
											</span>
										</TableCell>
										<TableCell className="text-sm tabular-nums">
											{responded} of {invited}
											{completion !== undefined && (
												<span className="text-muted-foreground"> · {completion}%</span>
											)}
										</TableCell>
										<TableCell className="text-right">
											<SurveyActions
												survey={survey}
												now={now}
												pending={pendingIds.has(survey.id)}
												onToggleActive={onToggleActive}
												onEnd={onEnd}
												onDelete={onDelete}
											>
												<DropdownMenuItem
													render={<DetailStackLink entry={surveyLevel(survey.id)} />}
												>
													<BarChart3 className="size-4" />
													View results
												</DropdownMenuItem>
											</SurveyActions>
										</TableCell>
									</TableRow>
								);
							})}
						</TableBody>
					)}
				</Table>
			</div>
			{state.status === "ready" && (
				<TablePagination
					page={state.page}
					totalPages={state.totalPages}
					onPageChange={state.onPageChange}
				/>
			)}
		</div>
	);
}
