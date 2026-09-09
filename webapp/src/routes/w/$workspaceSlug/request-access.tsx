import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, redirect, useNavigate } from "@tanstack/react-router";

import {
	checkWorkspaceOrganizationMembershipMutation,
	getMyWorkspaceAccessRequestsOptions,
	getMyWorkspaceAccessRequestsQueryKey,
	getWorkspaceAccessEntryOptions,
	getWorkspaceAccessFormOptions,
	listLinkedIdentitiesOptions,
	submitWorkspaceAccessRequestMutation,
	withdrawWorkspaceAccessRequestMutation,
} from "@/api/@tanstack/react-query.gen";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { WorkspaceAccessHistory } from "@/components/workspace-access/WorkspaceAccessHistory";
import { WorkspaceAccessRequestForm } from "@/components/workspace-access/WorkspaceAccessRequestForm";
import { useAuth } from "@/integrations/auth/AuthContext";
import { consentIsPending, resolveCurrentUser } from "@/integrations/auth/guard";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/w/$workspaceSlug/request-access")({
	staticData: { surface: "auth" },
	validateSearch: (search): { renew?: boolean } => ({
		renew: search.renew === true || search.renew === "true" ? true : undefined,
	}),
	beforeLoad: async ({ context, location }) => {
		const user = await resolveCurrentUser(context.queryClient);
		if (user && (await consentIsPending(context.queryClient))) {
			throw redirect({ to: "/consent", search: { returnTo: location.href } });
		}
	},
	component: WorkspaceAccessPage,
});

function WorkspaceAccessPage() {
	const { workspaceSlug } = Route.useParams();
	const { renew } = Route.useSearch();
	const path = { workspaceSlug };
	const returnTo = `/w/${workspaceSlug}/request-access${renew ? "?renew=true" : ""}`;
	const { userProfile, login, linkAccount } = useAuth();
	const navigate = useNavigate();
	const queryClient = useQueryClient();
	const entry = useQuery({ ...getWorkspaceAccessEntryOptions({ path }), retry: false });
	const identities = useQuery({ ...listLinkedIdentitiesOptions({}), enabled: !!userProfile });
	const primary = entry.data?.primaryProvider;
	const primaryLinked =
		primary &&
		identities.data?.some(
			(identity) =>
				identity.providerType === primary.providerType && identity.serverUrl === primary.serverUrl,
		);
	const history = useQuery({
		...getMyWorkspaceAccessRequestsOptions({ path }),
		enabled: !!userProfile,
		retry: false,
	});
	const admission = useMutation({
		...checkWorkspaceOrganizationMembershipMutation(),
		onSuccess: async (result) => {
			if (result.state === "ACTIVE" && !renew) {
				await queryClient.invalidateQueries();
				await navigate({ to: "/w/$workspaceSlug", params: path });
			}
		},
	});
	const form = useQuery({
		...getWorkspaceAccessFormOptions({ path }),
		enabled:
			!!primaryLinked &&
			(admission.data?.state === "REQUEST" ||
				admission.data?.state === "RENEWAL" ||
				(renew && admission.data?.state === "ACTIVE")),
		retry: false,
	});
	const refreshHistory = () =>
		queryClient.invalidateQueries({ queryKey: getMyWorkspaceAccessRequestsQueryKey({ path }) });
	const submit = useMutation({
		...submitWorkspaceAccessRequestMutation(),
		onSuccess: refreshHistory,
	});
	const withdraw = useMutation({
		...withdrawWorkspaceAccessRequestMutation(),
		onSuccess: refreshHistory,
	});
	const openRequest = history.data?.some(
		(request) => request.status === "SUBMITTED" || request.status === "CHANGES_REQUESTED",
	);
	const canAmend = history.data?.[0]?.status === "CHANGES_REQUESTED";
	const missingLinks = form.data?.requiredLinks.filter((link) => !link.linked) ?? [];

	return (
		<main className="mx-auto w-full max-w-2xl space-y-8 px-4 py-12">
			<h1 className="text-3xl font-semibold">
				{entry.data ? `Access to ${entry.data.workspaceName}` : "Workspace access"}
			</h1>
			{entry.isPending && <AccessLoading />}
			{entry.isError && (
				<p role="alert">
					This access page is unavailable. Check the workspace address or try again later.
				</p>
			)}
			{entry.data && (!entry.data.acceptingRequests || !primary) && (
				<p>This workspace is not accepting access requests.</p>
			)}
			{entry.data?.acceptingRequests && primary && (
				<>
					{!userProfile ? (
						<Button onClick={() => login(primary.registrationId, returnTo)}>
							Sign in with {primary.displayName}
						</Button>
					) : (
						<>
							{identities.isPending && <AccessLoading />}
							{identities.isError && (
								<p role="alert">
									Your linked accounts could not be loaded.{" "}
									<Button variant="outline" onClick={() => void identities.refetch()}>
										Try again
									</Button>
								</p>
							)}
							{identities.data && !primaryLinked && (
								<div className="space-y-3">
									<p>
										Connect {primary.displayName} to your existing Hephaestus account to continue.
									</p>
									<Button onClick={() => linkAccount(primary.registrationId, returnTo)}>
										Connect {primary.displayName}
									</Button>
								</div>
							)}
							{primaryLinked && (
								<div className="space-y-3">
									<p>
										Check whether you already belong to the workspace organization. Otherwise, you
										can request access or a renewal.
									</p>
									<Button disabled={admission.isPending} onClick={() => admission.mutate({ path })}>
										{admission.isPending && <Spinner />}
										{admission.isPending ? "Checking access…" : "Continue"}
									</Button>
									{admission.isError && <p role="alert">{problemDetailOf(admission.error)}</p>}
									{admission.data?.state === "CHECK_UNAVAILABLE" && (
										<p>
											The organization membership check is temporarily unavailable. Try again; no
											access has been granted.
										</p>
									)}
									{admission.data?.state === "MANAGED" && (
										<p>Your access is managed separately. Contact a workspace administrator.</p>
									)}
								</div>
							)}
							{form.isLoading && <AccessLoading />}
							{submit.isError && (
								<Button variant="outline" onClick={() => void form.refetch()}>
									Reload current policy
								</Button>
							)}
							{form.isError && (
								<p role="alert">
									{problemDetailOf(form.error)}{" "}
									<Button variant="outline" onClick={() => void form.refetch()}>
										Reload form
									</Button>
								</p>
							)}
							{form.data && (!openRequest || canAmend) && (
								<>
									{missingLinks.length > 0 && (
										<section className="space-y-3" aria-labelledby="required-accounts-heading">
											<h2 id="required-accounts-heading" className="text-xl font-semibold">
												Connect required accounts
											</h2>
											<p>
												Connect these accounts before filling in your application. You will return
												here after each connection.
											</p>
											{missingLinks.map((link) => (
												<Button
													key={link.registrationId}
													variant="outline"
													onClick={() => linkAccount(link.registrationId, returnTo)}
												>
													Connect {link.displayName}
													{link.teamId ? ` (${link.teamId})` : ""}
												</Button>
											))}
										</section>
									)}
									{!form.data.verifiedContactAvailable && (
										<p>
											A verified email address is required. Verify an address with your connected
											identity provider, then reconnect that account and reload this page.
										</p>
									)}
									{missingLinks.length === 0 && form.data.verifiedContactAvailable && (
										<WorkspaceAccessRequestForm
											key={`${workspaceSlug}-${form.data.policyVersion}`}
											form={form.data}
											pending={submit.isPending}
											error={submit.isError ? problemDetailOf(submit.error) : undefined}
											onSubmit={(body) => submit.mutate({ path, body })}
										/>
									)}
								</>
							)}
						</>
					)}
				</>
			)}
			{userProfile && (
				<>
					{history.isPending && <AccessLoading />}
					{history.isError && (
						<p role="alert">
							Your requests could not be loaded.{" "}
							<Button variant="outline" onClick={() => void history.refetch()}>
								Retry
							</Button>
						</p>
					)}
					{history.data && (
						<WorkspaceAccessHistory
							requests={history.data}
							withdrawingId={withdraw.isPending ? withdraw.variables.path.requestId : undefined}
							onWithdraw={(requestId) => withdraw.mutate({ path: { ...path, requestId } })}
						/>
					)}
					{history.data?.some((request) => request.status === "APPROVED") && !renew && (
						<Link
							to="/w/$workspaceSlug/request-access"
							params={path}
							search={{ renew: true }}
							className="block underline"
						>
							Request a renewal
						</Link>
					)}
					{withdraw.isError && <p role="alert">{problemDetailOf(withdraw.error)}</p>}
					<Link to="/settings" className="text-sm underline">
						Manage linked accounts and research consent
					</Link>
				</>
			)}
		</main>
	);
}

function AccessLoading() {
	return (
		<div className="space-y-4" aria-label="Loading workspace access" aria-busy="true">
			<Skeleton className="h-6 w-2/3" />
			<Skeleton className="h-12 w-full" />
			<Skeleton className="h-12 w-full" />
		</div>
	);
}
