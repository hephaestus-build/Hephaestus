import { Link } from "@tanstack/react-router";
import { useId } from "react";
import { useSpinDelay } from "spin-delay";

import type { WorkspaceAccessOffer } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

export type WorkspaceAccessState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; offers: WorkspaceAccessOffer[] };

export interface WorkspaceAccessSectionProps {
	state: WorkspaceAccessState;
	joiningWorkspaceId?: number;
	onJoin: (workspaceId: number) => void;
}

/** Pre-access onboarding contains only this account's verified organizational offers. */
export function WorkspaceAccessSection({
	state,
	joiningWorkspaceId,
	onJoin,
}: WorkspaceAccessSectionProps) {
	const headingId = useId();
	const showJoining = useSpinDelay(joiningWorkspaceId !== undefined, {
		delay: 1000,
		minDuration: 500,
	});
	return (
		<section className="space-y-4" aria-labelledby={headingId}>
			<div className="space-y-1">
				<h2 id={headingId} className="text-xl font-semibold">
					Workspace access
				</h2>
				<p className="text-sm text-muted-foreground">
					Your verified organizational identity can make you eligible for a workspace. Linking an
					account never grants ownership or lifts a suspension.
				</p>
			</div>
			{state.status === "loading" ? (
				<div className="space-y-3" role="group" aria-label="Loading workspace offers">
					<Skeleton className="h-16 w-full" />
					<Skeleton className="h-16 w-full" />
				</div>
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Couldn't check workspace eligibility"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : state.offers.length === 0 ? (
				<p className="text-sm text-muted-foreground">
					No current directory offers. Connect your organization's approved sign-in method below, or
					ask the workspace owner to confirm the policy and your eligibility. An owner can also
					grant access using your account ID.
				</p>
			) : (
				<ul className="space-y-3">
					{state.offers.map((offer) => (
						<li
							key={offer.workspaceId}
							className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4"
						>
							<div className="min-w-0 space-y-1">
								<h3 className="break-words font-medium">{offer.displayName}</h3>
								<p className="text-sm text-muted-foreground">
									{offer.suspended
										? "Your access is suspended. Ask the owner to restore it explicitly."
										: offer.joined
											? "You have workspace access."
											: "Your organizational identity is eligible."}
								</p>
							</div>
							{offer.joined ? (
								<Button
									variant="outline"
									render={<Link to="/w/$workspaceSlug" params={{ workspaceSlug: offer.slug }} />}
								>
									Open workspace
								</Button>
							) : (
								!offer.suspended && (
									<Button
										disabled={joiningWorkspaceId !== undefined}
										onClick={() => onJoin(offer.workspaceId)}
										aria-label={`Join ${offer.displayName}`}
									>
										{showJoining && joiningWorkspaceId === offer.workspaceId && <Spinner />}
										{showJoining && joiningWorkspaceId === offer.workspaceId
											? "Joining…"
											: "Join workspace"}
									</Button>
								)
							)}
						</li>
					))}
				</ul>
			)}
		</section>
	);
}
