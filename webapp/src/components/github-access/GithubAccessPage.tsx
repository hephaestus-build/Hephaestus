import { Link } from "@tanstack/react-router";
import { ShieldCheck } from "lucide-react";
import { useSpinDelay } from "spin-delay";

import type { GitHubAccessOffer } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Button, buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export type GithubAccessState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; offers: GitHubAccessOffer[] };

/** Only the signed-in person's offers are supplied; preferences never expand directory eligibility. */
export function GithubAccessPage({
	state,
	changingTargetId,
	onEnroll,
}: {
	state: GithubAccessState;
	changingTargetId?: number;
	onEnroll: (targetId: number, enrolled: boolean) => void;
}) {
	const showBusy = useSpinDelay(changingTargetId !== undefined, { delay: 1000, minDuration: 500 });
	return (
		<PageLayout>
			<PageHeader
				icon={<ShieldCheck />}
				title="Your GitHub access"
				description="Complete invitations and see which memberships are managed by your workspaces."
				actions={
					<Link className={buttonVariants({ variant: "outline" })} to="/settings">
						Linked accounts
					</Link>
				}
			/>
			<p className="text-sm text-muted-foreground">
				Link your GitHub.com identity in account settings. A pending invitation must be accepted on
				GitHub. Team access waits for organization membership; an email or username match does not
				establish eligibility.
			</p>
			{state.status === "loading" ? (
				<div className="space-y-4" role="group" aria-label="Loading your GitHub access">
					<Skeleton className="h-40 w-full" />
					<Skeleton className="h-40 w-full" />
				</div>
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Couldn't load your GitHub access"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : state.offers.length === 0 ? (
				<p className="rounded-lg border border-dashed p-6 text-sm text-muted-foreground">
					No GitHub targets are offered yet. Join your directory-approved workspace, link GitHub,
					and ask its owner to approve and reconcile the intended target.
				</p>
			) : (
				<ul className="space-y-4">
					{state.offers.map((offer) => (
						<li key={offer.targetId} className="space-y-3 rounded-lg border p-4">
							<h2 className="break-all font-semibold">
								{offer.organization} / {offer.scopeName ?? "Organization"}
							</h2>
							<p className="text-sm">Workspace: {offer.workspaceName}</p>
							<p className="text-sm">
								{offer.revocationRequested
									? "Removal pending — access remains until GitHub confirms it."
									: offer.externalState === "PENDING"
										? "Invitation pending — accept it on GitHub."
										: offer.externalState === "ACTIVE"
											? "Active GitHub membership confirmed."
											: offer.externalState === "ABSENT"
												? "No GitHub access was present at the last check."
												: offer.externalState === "WAITING_ORGANIZATION"
													? "Accept your organization invitation before team access can proceed."
													: offer.externalState === "PROTECTED"
														? "This access is protected and is not automatically managed."
														: "Access has not yet been confirmed."}
							</p>
							{offer.paused && (
								<p className="text-sm">
									Changes are paused, including removals. Existing access has not been revoked.
								</p>
							)}
							{offer.blocker && <p className="text-sm text-muted-foreground">{offer.blocker}</p>}
							<p className="text-sm text-muted-foreground">
								{offer.managed ? "Managed by your workspace" : "Not managed by Hephaestus"}. Last
								confirmed <RelativeTime value={offer.confirmedAt} fallback="not yet" />.
							</p>
							<div className="flex flex-wrap gap-2">
								{(offer.externalState === "PENDING" ||
									offer.externalState === "WAITING_ORGANIZATION") && (
									<a
										className={buttonVariants()}
										href={offer.invitationUrl}
										target="_blank"
										rel="noreferrer"
									>
										Open GitHub invitation
									</a>
								)}
								<Button
									variant="outline"
									disabled={changingTargetId !== undefined}
									onClick={() => onEnroll(offer.targetId, !offer.enrolled)}
								>
									{showBusy && changingTargetId === offer.targetId && <Spinner />}
									{changingTargetId === offer.targetId
										? "Updating…"
										: offer.enrolled
											? "Leave target"
											: "Rejoin if eligible"}
								</Button>
							</div>
						</li>
					))}
				</ul>
			)}
		</PageLayout>
	);
}
