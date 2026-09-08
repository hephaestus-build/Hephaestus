import { ShieldCheck } from "lucide-react";
import { useSpinDelay } from "spin-delay";
import type { GitHubAccessHandoffPreview } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export type GithubApprovalState =
	| { status: "loading" }
	| { status: "invalid" }
	| { status: "signed-out"; onSignIn: () => void }
	| { status: "error"; error: unknown; onRetry: () => void; onLinkAccount: () => void }
	| {
			status: "review";
			preview?: GitHubAccessHandoffPreview;
			onPreview: () => void;
			onApprove: () => void;
			error?: unknown;
	  }
	| { status: "complete" };

/** The recipient authorizes one external scope, without joining or taking ownership of the workspace. */
export function GithubAccessApprovalPage({
	state,
	busy,
}: {
	state: GithubApprovalState;
	busy: boolean;
}) {
	const showBusy = useSpinDelay(busy, { delay: 1000, minDuration: 500 });
	return (
		<PageLayout>
			<PageHeader
				icon={<ShieldCheck />}
				title="Authorize GitHub access"
				description="Review the workspace asking to manage a scope in your GitHub organization."
			/>
			{state.status === "loading" ? (
				<div aria-label="Loading approval">
					<Skeleton className="h-48 w-full" />
				</div>
			) : state.status === "invalid" ? (
				<p className="rounded-lg border p-6">
					This approval link is missing or expired. Ask the workspace owner for a new private link.
					Never put the link in a ticket or log.
				</p>
			) : state.status === "signed-out" ? (
				<section className="space-y-4 rounded-lg border p-6">
					<p>
						Sign in as yourself, then link your GitHub.com identity. You must be an active GitHub
						organization owner, but you do not need to join the requesting workspace.
					</p>
					<Button onClick={state.onSignIn}>Sign in to review</Button>
				</section>
			) : state.status === "error" ? (
				<div className="space-y-4">
					<QueryErrorAlert
						title="Couldn't review this approval"
						error={state.error}
						onRetry={state.onRetry}
					/>
					<Button variant="outline" onClick={state.onLinkAccount}>
						Check linked accounts
					</Button>
				</div>
			) : state.status === "complete" ? (
				<section className="space-y-3 rounded-lg border p-6">
					<h2 className="text-xl font-semibold">Organization authorization recorded</h2>
					<p>
						The workspace owner must now preview and approve the policy before new grants can begin.
						This one-use link is no longer valid.
					</p>
				</section>
			) : (
				<section className="space-y-4 rounded-lg border p-6">
					{state.preview ? (
						<>
							<h2 className="text-xl font-semibold">{state.preview.workspaceName}</h2>
							<dl className="grid gap-2 text-sm">
								<dt className="font-medium">Requested GitHub scope</dt>
								<dd className="break-all">
									{state.preview.organization}
									{state.preview.team ? ` / ${state.preview.team}` : " / Organization membership"}
								</dd>
								<dt className="font-medium">Access App installation</dt>
								<dd>{state.preview.installationId}</dd>
								<dt className="font-medium">Directory groups</dt>
								<dd className="break-all">{state.preview.groupIds.join(", ")}</dd>
							</dl>
							<p className="text-sm">
								You authorize Hephaestus Access to manage this scope for the named workspace under
								its approved directory policy. Existing access still requires explicit adoption.
								Your consent does not make you a workspace member or change the normal repository
								App's permissions.
							</p>
							<p className="text-sm text-muted-foreground">
								You must sign in recently with an identity linked to this account. Impersonation
								cannot authorize access.
							</p>
							{state.error !== undefined && (
								<QueryErrorAlert title="Authorization was not completed" error={state.error} />
							)}
							<Button disabled={busy} onClick={state.onApprove}>
								{showBusy && <Spinner />}
								{busy ? "Verifying ownership…" : "Authorize this workspace and scope"}
							</Button>
						</>
					) : (
						<>
							<p>
								Inspect the requested workspace and scope before making a decision. No private
								GitHub membership inventory is read before your organization ownership is verified.
							</p>
							<Button disabled={busy} onClick={state.onPreview}>
								{showBusy && <Spinner />}
								{busy ? "Loading request…" : "Review approval request"}
							</Button>
						</>
					)}
				</section>
			)}
		</PageLayout>
	);
}
