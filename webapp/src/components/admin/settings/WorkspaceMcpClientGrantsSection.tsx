import { useId } from "react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { formatDate } from "@/lib/dates";

/** One connected MCP client, as the workspace's admins see it. */
export interface McpClientGrantView {
	id: string;
	clientHost: string;
	clientName: string;
	/** Who approved the client, as the members list names them. */
	approvedBy: string;
	scopes: string[];
	approvedAt?: Date;
}

export interface WorkspaceMcpClientGrantsSectionProps {
	grants?: McpClientGrantView[];
	loading?: boolean;
	error?: string;
	/** The grant whose disconnection is in flight. */
	revokingId?: string;
	onRetry: () => void;
	onRevoke: (grantId: string) => void;
}

/** The MCP clients that members connected to this workspace, which an admin can disconnect (ADR 0110). */
export function WorkspaceMcpClientGrantsSection({
	grants,
	loading = false,
	error,
	revokingId,
	onRetry,
	onRevoke,
}: WorkspaceMcpClientGrantsSectionProps) {
	const id = useId();
	return (
		<section aria-labelledby={`${id}-heading`} aria-busy={loading} className="space-y-3">
			<h2 id={`${id}-heading`} className="text-lg font-semibold">
				Connected MCP clients
			</h2>
			<p className="text-sm text-muted-foreground">
				Programs such as Claude Code that an admin allowed to read and configure this workspace's practices.
				Disconnecting one ends its access at once.
			</p>
			{loading ? (
				<Skeleton className="h-16 w-full" />
			) : grants === undefined ? null : grants.length === 0 ? (
				<p>No client is connected.</p>
			) : (
				<ul className="divide-y rounded-md border">
					{grants.map((grant) => (
						<li key={grant.id} className="flex items-center justify-between gap-4 p-3">
							<div className="min-w-0">
								<p className="font-medium">{grant.clientHost}</p>
								<p className="text-sm text-muted-foreground">
									Calls itself {grant.clientName}. Allowed by {grant.approvedBy}
									{grant.approvedAt === undefined ? "" : ` on ${formatDate(grant.approvedAt)}`}.
								</p>
							</div>
							<Button
								variant="outline"
								onClick={() => onRevoke(grant.id)}
								disabled={revokingId !== undefined}
								aria-busy={revokingId === grant.id}
								aria-label={`Disconnect ${grant.clientHost}`}
							>
								{revokingId === grant.id && <Spinner />}
								Disconnect
							</Button>
						</li>
					))}
				</ul>
			)}
			<div role="alert" aria-atomic="true">
				{error !== undefined && <p>{error}</p>}
			</div>
			{error !== undefined && (
				<Button variant="outline" onClick={onRetry}>
					Retry
				</Button>
			)}
		</section>
	);
}
