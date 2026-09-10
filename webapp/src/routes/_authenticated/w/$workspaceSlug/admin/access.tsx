import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";

import {
	configureWorkspaceAccessPolicyMutation,
	getAllTeamsOptions,
	getWorkspaceAccessNotificationsOptions,
	getWorkspaceAccessNotificationsQueryKey,
	getWorkspaceAccessPolicyOptions,
	getWorkspaceAccessPolicyQueryKey,
	getWorkspaceAccessRequestsOptions,
	getWorkspaceAccessRequestsQueryKey,
	getWorkspaceAccessReviewOptionsOptions,
	listAccountIdentityProvidersOptions,
	retryWorkspaceAccessNotificationMutation,
	reviewWorkspaceAccessRequestMutation,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceAccessRequest } from "@/api/types.gen";
import { WorkspaceAccessDeliveries } from "@/components/admin/workspace-access/WorkspaceAccessDeliveries";
import { WorkspaceAccessPolicyForm } from "@/components/admin/workspace-access/WorkspaceAccessPolicyForm";
import { WorkspaceAccessReview } from "@/components/admin/workspace-access/WorkspaceAccessReview";
import { Button } from "@/components/ui/button";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { useWorkspaceAccess } from "@/hooks/use-workspace-access";
import { useAuth } from "@/integrations/auth/AuthContext";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/access")({
	head: workspaceAdminHead("Access"),
	component: WorkspaceAccessAdminPage,
});

const statusOptions = [
	{ value: "SUBMITTED", label: "Awaiting review" },
	{ value: "CHANGES_REQUESTED", label: "Changes requested" },
	{ value: "APPROVED", label: "Approved" },
	{ value: "REJECTED", label: "Not approved" },
	{ value: "CANCELLED", label: "Withdrawn" },
	{ value: "SUPERSEDED", label: "Replaced" },
] satisfies { value: WorkspaceAccessRequest["status"]; label: string }[];

function WorkspaceAccessAdminPage() {
	const { workspaceSlug } = Route.useParams();
	const path = { workspaceSlug };
	const queryClient = useQueryClient();
	const { role } = useWorkspaceAccess();
	const { userProfile } = useAuth();
	const [tab, setTab] = useState<"requests" | "policy">("requests");
	const [status, setStatus] = useState<WorkspaceAccessRequest["status"]>("SUBMITTED");
	const [page, setPage] = useState(0);
	const [selectedId, setSelectedId] = useState<number>();
	const policy = useQuery(getWorkspaceAccessPolicyOptions({ path }));
	const providers = useQuery({
		...listAccountIdentityProvidersOptions({}),
		enabled: tab === "policy" && role === "OWNER",
	});
	const teams = useQuery({
		...getAllTeamsOptions({ path }),
		enabled: tab === "policy" && role === "OWNER",
	});
	const query = { status, page };
	const requests = useQuery(getWorkspaceAccessRequestsOptions({ path, query }));
	const options = useQuery(getWorkspaceAccessReviewOptionsOptions({ path }));
	const selected = requests.data?.find((request) => request.id === selectedId);
	const notifications = useQuery({
		...getWorkspaceAccessNotificationsOptions({ path: { ...path, requestId: selectedId ?? 0 } }),
		enabled: selectedId !== undefined,
	});
	const configure = useMutation({
		...configureWorkspaceAccessPolicyMutation(),
		onSuccess: async () => {
			await Promise.all([
				queryClient.invalidateQueries({ queryKey: getWorkspaceAccessPolicyQueryKey({ path }) }),
				queryClient.invalidateQueries(getWorkspaceAccessReviewOptionsOptions({ path })),
			]);
		},
	});
	const review = useMutation({
		...reviewWorkspaceAccessRequestMutation(),
		onSuccess: async () => {
			await Promise.all([
				queryClient.invalidateQueries({
					queryKey: getWorkspaceAccessRequestsQueryKey({ path, query }),
				}),
				queryClient.invalidateQueries({
					queryKey: getWorkspaceAccessNotificationsQueryKey({
						path: { ...path, requestId: selectedId ?? 0 },
					}),
				}),
			]);
		},
	});
	const retry = useMutation({
		...retryWorkspaceAccessNotificationMutation(),
		onSuccess: () =>
			queryClient.invalidateQueries({
				queryKey: getWorkspaceAccessNotificationsQueryKey({
					path: { ...path, requestId: selectedId ?? 0 },
				}),
			}),
	});

	return (
		<div className="mx-auto w-full max-w-4xl space-y-6 p-6">
			<h1 className="text-3xl font-semibold">Workspace access</h1>
			<Link to="/w/$workspaceSlug/request-access" params={path} className="underline">
				Open the applicant access page
			</Link>
			<div className="flex gap-2">
				<Button
					variant={tab === "requests" ? "default" : "outline"}
					onClick={() => setTab("requests")}
				>
					Access requests
				</Button>
				<Button variant={tab === "policy" ? "default" : "outline"} onClick={() => setTab("policy")}>
					Request policy
				</Button>
			</div>
			{tab === "policy" ? (
				<>
					{policy.isError && <p role="alert">{problemDetailOf(policy.error)}</p>}
					{policy.isPending && <AccessAdminLoading />}
					{role !== "OWNER" && (
						<p>
							Only a workspace owner can configure access requests. Requests are{" "}
							{policy.data?.enabled ? "enabled" : "paused"}.
						</p>
					)}
					{role === "OWNER" && (
						<>
							{(providers.isPending || teams.isPending) && <AccessAdminLoading />}
							{(providers.isError || teams.isError) && (
								<p role="alert">
									Provider or team options could not be loaded.{" "}
									<Button
										variant="outline"
										onClick={() => {
											void providers.refetch();
											void teams.refetch();
										}}
									>
										Retry
									</Button>
								</p>
							)}
							{policy.data && providers.data && teams.data && (
								<WorkspaceAccessPolicyForm
									key={`${workspaceSlug}-${policy.data.version ?? "new"}`}
									policy={policy.data}
									providers={providers.data}
									teams={teams.data}
									pending={configure.isPending}
									error={configure.isError ? problemDetailOf(configure.error) : undefined}
									onSave={(body) => configure.mutate({ path, body })}
								/>
							)}
							{configure.isSuccess && <p>Access policy saved.</p>}
						</>
					)}
				</>
			) : (
				<>
					<Select
						value={status}
						onValueChange={(value) => {
							if (value !== null) {
								setStatus(value);
								setPage(0);
								setSelectedId(undefined);
							}
						}}
						items={statusOptions}
					>
						<SelectTrigger aria-label="Request status" className="w-full sm:w-64">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-label="Request status">
							{statusOptions.map((option) => (
								<SelectItem key={option.value} value={option.value}>
									{option.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
					{requests.isPending && <AccessAdminLoading />}
					{requests.isError && (
						<p role="alert">
							{problemDetailOf(requests.error)}{" "}
							<Button variant="outline" onClick={() => void requests.refetch()}>
								Retry requests
							</Button>
						</p>
					)}
					{requests.data?.length === 0 && <p>No requests with this status.</p>}
					{requests.data && (
						<ul className="space-y-2">
							{requests.data.map((request) => (
								<li key={request.id}>
									<Button
										variant={selectedId === request.id ? "secondary" : "outline"}
										onClick={() => {
											setSelectedId(request.id);
											review.reset();
										}}
									>
										Request #{request.id} · {request.displayName}
									</Button>
								</li>
							))}
						</ul>
					)}
					<div className="flex items-center gap-3">
						<Button
							variant="outline"
							disabled={page === 0 || requests.isFetching}
							onClick={() => {
								setPage(page - 1);
								setSelectedId(undefined);
							}}
						>
							Previous page
						</Button>
						<span>Page {page + 1}</span>
						<Button
							variant="outline"
							disabled={!requests.data || requests.data.length < 50 || requests.isFetching}
							onClick={() => {
								setPage(page + 1);
								setSelectedId(undefined);
							}}
						>
							Next page
						</Button>
					</div>
					{selected && options.isPending && <AccessAdminLoading />}
					{selected && options.isError && (
						<p role="alert">
							Review options could not be loaded.{" "}
							<Button variant="outline" onClick={() => void options.refetch()}>
								Reload options
							</Button>
						</p>
					)}
					{selected && (options.data ?? options.isError) && (
						<WorkspaceAccessReview
							key={`${selected.id}-${selected.version}`}
							request={selected}
							options={
								options.data ?? { maintainers: [], requestableTeams: [], maximumDurationDays: 0 }
							}
							canReview={String(selected.accountId) !== userProfile?.id}
							pending={review.isPending}
							error={review.isError ? problemDetailOf(review.error) : undefined}
							onReview={(body) =>
								review.mutate({ path: { ...path, requestId: selected.id }, body })
							}
						/>
					)}
					{review.isSuccess && (
						<p>Decision saved. Select its new status to see the updated request.</p>
					)}
					{selectedId !== undefined && (
						<>
							{notifications.isPending && <AccessAdminLoading />}
							{notifications.isError && (
								<p role="alert">
									Email delivery status could not be loaded.{" "}
									<Button variant="outline" onClick={() => void notifications.refetch()}>
										Retry delivery status
									</Button>
								</p>
							)}
							{notifications.data && (
								<WorkspaceAccessDeliveries
									notifications={notifications.data}
									retryingId={retry.isPending ? retry.variables.path.notificationId : undefined}
									onRetry={(notificationId) => retry.mutate({ path: { ...path, notificationId } })}
								/>
							)}
							{retry.isError && <p role="alert">{problemDetailOf(retry.error)}</p>}
						</>
					)}
				</>
			)}
		</div>
	);
}

function AccessAdminLoading() {
	return (
		<div className="space-y-3" aria-busy="true" aria-label="Loading access administration">
			<Skeleton className="h-8 w-2/3" />
			<Skeleton className="h-16 w-full" />
			<Skeleton className="h-16 w-full" />
		</div>
	);
}
