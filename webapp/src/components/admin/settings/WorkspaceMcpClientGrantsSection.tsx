import { useId } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDate, formatDateTime } from "@/lib/dates";

/** One connected MCP client, as the workspace's admins see it. */
export interface McpClientGrantView {
	id: string;
	clientHost: string;
	clientName: string;
	/** Who approved the client, as the members list names them. */
	approvedBy: string;
	scopes: string[];
	approvedAt?: Date;
	/** The approval was confirmed with a passkey, and the client's access ends at this time. */
	passkeyBackedUntil?: Date;
}

export interface WorkspaceMcpClientGrantsSectionProps {
	grants?: McpClientGrantView[];
	loading?: boolean;
	/** Why the list could not be read. */
	loadError?: unknown;
	/** Why the last disconnection failed. */
	revokeError?: unknown;
	/** The grant whose disconnection is in flight. */
	revokingId?: string;
	onRetry: () => void;
	onRevoke: (grantId: string) => void;
}

/** The MCP clients that admins connected to this workspace, which an admin can disconnect (ADR 0110). */
export function WorkspaceMcpClientGrantsSection({
	grants,
	loading = false,
	loadError,
	revokeError,
	revokingId,
	onRetry,
	onRevoke,
}: WorkspaceMcpClientGrantsSectionProps) {
	const id = useId();
	// A passkey-confirmed access that already ended reads as ended, not as one that ends.
	const now = useNow();
	return (
		<section aria-labelledby={`${id}-heading`}>
			<h2 id={`${id}-heading`} className="text-lg font-semibold">
				Connected MCP clients
			</h2>
			<p className="mb-4 max-w-2xl text-sm text-muted-foreground">
				Programs such as Claude Code that an admin allowed to read, or to change, this workspace’s
				practice configuration. Disconnecting one ends its access at once.
			</p>
			<div className="space-y-3">
				{loading && (
					<ul className="rounded-xl border bg-card" aria-busy="true" aria-hidden="true">
						<Item render={<li />} variant="row">
							<ItemContent>
								<Skeleton className="h-4 w-32" />
								<Skeleton className="h-3 w-56" />
							</ItemContent>
							<ItemActions>
								<Skeleton className="h-8 w-24" />
							</ItemActions>
						</Item>
					</ul>
				)}
				{loadError !== undefined && (
					<QueryErrorAlert
						error={loadError}
						title="We could not load the connected clients"
						onRetry={onRetry}
					/>
				)}
				{revokeError !== undefined && (
					<QueryErrorAlert error={revokeError} title="We could not disconnect the client" />
				)}
				{grants?.length === 0 && (
					<p className="text-sm text-muted-foreground">No client is connected.</p>
				)}
				{grants !== undefined && grants.length > 0 && (
					<ul className="rounded-xl border bg-card">
						{grants.map((grant) => (
							<Item key={grant.id} render={<li />} variant="row">
								<ItemContent>
									<ItemTitle>{grant.clientHost}</ItemTitle>
									{/* Outside the clamped description, so a long name never hides what the client may do. */}
									<p className="text-sm font-medium">
										{grant.scopes.includes("practices:write")
											? "Reads and changes the practice configuration"
											: "Reads the practice configuration"}
									</p>
									<ItemDescription>
										Calls itself {grant.clientName}. Allowed by {grant.approvedBy}
										{grant.approvedAt === undefined ? "" : ` on ${formatDate(grant.approvedAt)}`}.
									</ItemDescription>
									{/* Outside the clamped description, so a long name never hides when the access ends. */}
									{grant.passkeyBackedUntil !== undefined && (
										<p className="text-sm text-muted-foreground">
											Confirmed with a passkey. Its access{" "}
											{grant.passkeyBackedUntil.getTime() > now ? "ends" : "ended"}{" "}
											{formatDateTime(grant.passkeyBackedUntil)}.
										</p>
									)}
								</ItemContent>
								<ItemActions>
									<Button
										pending={revokingId === grant.id}
										variant="outline"
										size="sm"
										disabled={revokingId !== undefined}
										focusableWhenDisabled
										aria-label={`Disconnect ${grant.clientHost}`}
										onClick={() => onRevoke(grant.id)}
									>
										Disconnect
									</Button>
								</ItemActions>
							</Item>
						))}
					</ul>
				)}
			</div>
		</section>
	);
}
