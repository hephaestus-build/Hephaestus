import type { WorkspaceAccessRequest } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

const statusLabels = {
	SUBMITTED: "Awaiting review",
	CHANGES_REQUESTED: "Changes requested",
	APPROVED: "Approved",
	REJECTED: "Not approved",
	CANCELLED: "Withdrawn",
	SUPERSEDED: "Replaced by a newer request",
} satisfies Record<WorkspaceAccessRequest["status"], string>;

interface WorkspaceAccessHistoryProps {
	requests: WorkspaceAccessRequest[];
	withdrawingId?: number;
	onWithdraw: (id: number) => void;
}

export function WorkspaceAccessHistory({
	requests,
	withdrawingId,
	onWithdraw,
}: WorkspaceAccessHistoryProps) {
	const now = useNow();
	return (
		<section className="space-y-4" aria-labelledby="access-history-heading">
			<h2 id="access-history-heading" className="text-xl font-semibold">
				Your requests
			</h2>
			{requests.length === 0 ? (
				<p className="text-muted-foreground">You have not requested access to this workspace.</p>
			) : (
				<ol className="space-y-4">
					{requests.map((request) => (
						<li key={request.id} className="rounded-lg border p-4 space-y-2">
							<h3 className="font-semibold">
								Request #{request.id} · {statusLabels[request.status]}
							</h3>
							<p className="text-sm text-muted-foreground">
								Submitted {request.submittedAt.toLocaleDateString()}
							</p>
							{request.decisionComment && (
								<p className="whitespace-pre-wrap">{request.decisionComment}</p>
							)}
							{request.status === "APPROVED" && request.effectiveExpiresAt && (
								<p>
									{request.effectiveExpiresAt.getTime() <= now
										? "Access expired"
										: request.accessActive
											? "Access ends"
											: "Access is inactive; approved deadline"}{" "}
									{request.effectiveExpiresAt.toLocaleString()}. A renewal request does not extend
									this deadline.
								</p>
							)}
							{(request.status === "SUBMITTED" || request.status === "CHANGES_REQUESTED") && (
								<Button
									variant="outline"
									disabled={withdrawingId !== undefined}
									onClick={() => onWithdraw(request.id)}
								>
									{withdrawingId === request.id && <Spinner />}
									{withdrawingId === request.id
										? "Withdrawing…"
										: `Withdraw request #${request.id}`}
								</Button>
							)}
						</li>
					))}
				</ol>
			)}
		</section>
	);
}
