import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { toast } from "sonner";
import { useNow } from "@/components/common/use-now";
import { productSurveyAvailability } from "@/components/feedback/product-survey-status";
import { productSurveyQueryScope } from "@/hooks/use-product-feedback";

import {
	adminCreateProductSurveyMutation,
	adminUpdateProductSurveyStatusMutation,
	adminListProductFeedbackOptions,
	adminListProductSurveyResponsesOptions,
	adminListProductSurveysOptions,
	adminListProductSurveysQueryKey,
	adminListWorkspacesOptions,
} from "@/api/@tanstack/react-query.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";
import { ProductSurveyComposer } from "@/components/feedback/ProductSurveyComposer";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

export const Route = createFileRoute("/_authenticated/admin/feedback")({
	component: AdminProductFeedbackPage,
});

function AdminProductFeedbackPage() {
	const queryClient = useQueryClient();
	const now = useNow();
	const [composerVersion, setComposerVersion] = useState(0);
	const [feedbackPage, setFeedbackPage] = useState(0);
	const [responsePage, setResponsePage] = useState(0);
	const [surveyPage, setSurveyPage] = useState(0);
	const feedback = useQuery(
		adminListProductFeedbackOptions({ query: { page: feedbackPage, size: 20 } }),
	);
	const surveys = useQuery(
		adminListProductSurveysOptions({ query: { page: surveyPage, size: 20 } }),
	);
	const responses = useQuery(
		adminListProductSurveyResponsesOptions({ query: { page: responsePage, size: 20 } }),
	);
	const feedbackItems = feedback.data?.content ?? [];
	const surveyItems = surveys.data?.content ?? [];
	const responseItems = responses.data?.content ?? [];
	const workspaces = useQuery(adminListWorkspacesOptions());
	const create = useMutation({
		...adminCreateProductSurveyMutation(),
		retry: false,
		onSuccess: () => {
			toast.success("Survey published.");
			setComposerVersion((version) => version + 1);
			void queryClient.invalidateQueries({ queryKey: adminListProductSurveysQueryKey() });
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
		},
		onError: () => toast.error("Couldn't publish the survey."),
	});
	const status = useMutation({
		...adminUpdateProductSurveyStatusMutation(),
		retry: false,
		onSuccess: () => {
			void queryClient.invalidateQueries({ queryKey: adminListProductSurveysQueryKey() });
			void queryClient.invalidateQueries({ queryKey: productSurveyQueryScope() });
		},
		onError: () => toast.error("Couldn't change survey availability. Please try again."),
	});
	return (
		<div className="mx-auto w-full max-w-6xl space-y-6">
			<div>
				<h1 className="text-3xl font-bold">Product feedback</h1>
				<p className="text-muted-foreground">
					First-party survey authoring and the instance feedback inbox.
				</p>
			</div>
			<Tabs defaultValue="inbox">
				<TabsList>
					<TabsTrigger value="inbox">Feedback</TabsTrigger>
					<TabsTrigger value="responses">Survey responses</TabsTrigger>
					<TabsTrigger value="surveys">Surveys</TabsTrigger>
				</TabsList>
				<TabsContent value="inbox" className="space-y-3" aria-busy={feedback.isLoading}>
					{feedback.isError ? (
						<QueryErrorAlert
							error={feedback.error}
							title="Feedback couldn't be loaded"
							onRetry={() => void feedback.refetch()}
						/>
					) : null}
					{feedbackItems.map((item) => (
						<Card key={item.id}>
							<CardHeader>
								<CardTitle className="text-base">
									{item.kind === "BUG" ? "Bug report" : "Feedback"}
								</CardTitle>
								<CardDescription>
									{item.createdAt?.toLocaleString() ?? "Unknown time"} · account {item.accountId}
									{item.workspaceId ? ` · workspace ${item.workspaceId}` : ""}
								</CardDescription>
							</CardHeader>
							<CardContent>
								<p className="break-words whitespace-pre-wrap">{item.message}</p>
								{item.pagePath ? (
									<p className="mt-2 break-all text-sm text-muted-foreground">
										Page: {item.pagePath}
									</p>
								) : null}
							</CardContent>
						</Card>
					))}
					{feedback.isLoading ? <Skeleton className="h-32 w-full" /> : null}
					{feedback.isSuccess && feedbackItems.length === 0 ? <p>No feedback yet.</p> : null}
					<TablePagination
						page={feedbackPage}
						totalPages={feedback.data?.page?.totalPages ?? 0}
						onPageChange={setFeedbackPage}
					/>
				</TabsContent>
				<TabsContent value="responses" className="space-y-3" aria-busy={responses.isLoading}>
					{responses.isError ? (
						<QueryErrorAlert
							error={responses.error}
							title="Survey responses couldn't be loaded"
							onRetry={() => void responses.refetch()}
						/>
					) : null}
					{responseItems.map((item) => (
						<Card key={item.id}>
							<CardHeader>
								<CardTitle className="text-base">
									{item.disposition === "DISMISSED" ? "Dismissed" : "Response"}
								</CardTitle>
								<CardDescription>
									{item.createdAt?.toLocaleString() ?? "Unknown time"} · {item.surveyTitle} ·
									account {item.accountId}
								</CardDescription>
							</CardHeader>
							{item.answers ? (
								<CardContent>
									<dl className="space-y-3">
										{item.questions.map((question) => {
											const answer = item.answers?.[question.id];
											return answer ? (
												<div key={question.id}>
													<dt className="font-medium">{question.prompt}</dt>
													<dd className="break-words whitespace-pre-wrap text-sm">{answer}</dd>
												</div>
											) : null;
										})}
									</dl>
								</CardContent>
							) : null}
						</Card>
					))}
					{responses.isLoading ? <Skeleton className="h-32 w-full" /> : null}
					{responses.isSuccess && responseItems.length === 0 ? (
						<p>No survey responses yet.</p>
					) : null}
					<TablePagination
						page={responsePage}
						totalPages={responses.data?.page?.totalPages ?? 0}
						onPageChange={setResponsePage}
					/>
				</TabsContent>
				<TabsContent
					value="surveys"
					keepMounted
					className="space-y-6"
					aria-busy={surveys.isLoading}
				>
					{surveys.isError ? (
						<QueryErrorAlert
							error={surveys.error}
							title="Surveys couldn't be loaded"
							onRetry={() => void surveys.refetch()}
						/>
					) : null}
					{workspaces.isError ? (
						<QueryErrorAlert
							error={workspaces.error}
							title="Workspace audiences couldn't be loaded"
							onRetry={() => void workspaces.refetch()}
						/>
					) : null}
					<Card>
						<CardHeader>
							<CardTitle>Publish a survey</CardTitle>
							<CardDescription>
								Publish a focused survey with optional follow-up questions and a bounded schedule.
							</CardDescription>
						</CardHeader>
						<CardContent>
							{workspaces.isPending ? (
								<Skeleton className="h-64 w-full" />
							) : workspaces.data ? (
								<ProductSurveyComposer
									key={composerVersion}
									workspaces={workspaces.data}
									isPending={create.isPending}
									error={
										create.isError
											? "Couldn't publish. Your draft is still here; review the fields and try again."
											: undefined
									}
									onSubmit={async (body) => {
										try {
											await create.mutateAsync({ body });
											return true;
										} catch {
											return false;
										}
									}}
								/>
							) : null}
						</CardContent>
					</Card>
					{surveyItems.map((survey) => (
						<Card key={survey.id}>
							<CardHeader>
								<CardTitle className="text-base">{survey.title}</CardTitle>
								<CardDescription>
									{survey.workspaceId ? `Workspace ${survey.workspaceId}` : "All workspaces"} ·{" "}
									{survey.createdAt?.toLocaleString() ?? "Unknown time"}
								</CardDescription>
							</CardHeader>
							<CardContent className="space-y-3">
								<p>{survey.description}</p>
								<p className="text-sm text-muted-foreground">
									{survey.questions.length} questions · {productSurveyAvailability(survey, now)}
									<br />
									Starts {survey.startsAt.toLocaleString()}
									{survey.endsAt ? ` · Ends ${survey.endsAt.toLocaleString()}` : " · No end date"}
								</p>
								<Button
									variant="outline"
									size="sm"
									disabled={status.isPending}
									onClick={() =>
										status.mutate({
											path: { surveyId: survey.id },
											body: { active: !survey.active },
										})
									}
								>
									{status.isPending && status.variables.path.surveyId === survey.id
										? "Saving…"
										: survey.active
											? "Pause survey"
											: "Resume survey"}
								</Button>
							</CardContent>
						</Card>
					))}
					{surveys.isLoading ? <Skeleton className="h-32 w-full" /> : null}
					{surveys.isSuccess && surveyItems.length === 0 ? <p>No surveys yet.</p> : null}
					<TablePagination
						page={surveyPage}
						totalPages={surveys.data?.page?.totalPages ?? 0}
						onPageChange={setSurveyPage}
					/>
				</TabsContent>
			</Tabs>
		</div>
	);
}
