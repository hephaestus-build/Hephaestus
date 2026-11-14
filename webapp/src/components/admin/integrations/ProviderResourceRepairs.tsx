import { Link } from "@tanstack/react-router";
import { ShieldAlertIcon } from "lucide-react";
import { useId, useState } from "react";

import type { ProviderResourceRepair } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";

const RESOURCE_LABELS: Record<ProviderResourceRepair["resourceType"], string> = {
	INSTALLATION: "App installation",
	GROUP_HOOK: "Group webhook",
	BOT_TOKEN: "Bot token",
	BOT_USER: "Bot user",
	SUBSCRIPTION: "Webhook subscription",
	REGISTRATION: "Unconfirmed registration",
	OAUTH_EXCHANGE: "Slack authorization",
};

const STATE_COPY: Record<ProviderResourceRepair["state"], string> = {
	REGISTERING:
		"The provider may still finish creating this resource. Its outcome needs confirmation.",
	PENDING: "Removal is waiting to run. This resource cannot be reused yet.",
	CHECKING: "Hephaestus is checking whether the old resource is still owned and present.",
	CONFIRMATION_REQUIRED:
		"Removal may still finish after an earlier timeout. An instance admin must confirm the old operation is complete before reuse.",
	DISPATCHING:
		"The removal request may have reached the provider. Hephaestus is waiting for its result.",
	AWAITING_CONFIRMATION:
		"The provider has not confirmed that removal is complete. Use a distinct resource when reconnecting.",
	NEEDS_AUTHORIZATION:
		"Hephaestus cannot verify removal with the available credentials. An instance admin must check at the provider.",
};

function repairStateCopy(repair: ProviderResourceRepair) {
	if (repair.resourceType !== "OAUTH_EXCHANGE") {
		return STATE_COPY[repair.state];
	}
	if (repair.state === "REGISTERING") {
		return "Slack is completing this authorization. Wait for its result before confirming the outcome.";
	}
	return "This authorization has an uncertain outcome. An instance admin must confirm its outcome at Slack before releasing this block.";
}

/** Why a removal has no release button here. */
const RELEASE_UNAVAILABLE_COPY = {
	registering:
		"Wait for the authorization or registration to finish before confirming its outcome.",
	"at-connection":
		"Confirm that removal is complete, then release the block in connection administration.",
	"instance-admin": "An instance admin can release the block after confirming removal is complete.",
} satisfies Record<Exclude<ProviderRepairItem["release"], "available">, string>;

export interface ProviderRepairItem {
	repair: ProviderResourceRepair;
	/**
	 * Whether this console releases the block. Otherwise it says where, or when, someone can:
	 * the instance console releases a removal that has an owning connection in that connection.
	 */
	release: "available" | "registering" | "at-connection" | "instance-admin";
}

export interface ProviderResourceRepairsProps {
	state:
		| { status: "loading" }
		| { status: "error"; error: unknown; onRetry: () => void }
		| { status: "ready"; items: ProviderRepairItem[] };
	onRelease?: (repair: ProviderResourceRepair) => Promise<void>;
	isReleasing?: boolean;
	page?: number;
	totalPages?: number;
	onPageChange?: (page: number) => void;
}

function owningPage(kind: ProviderResourceRepair["kind"]) {
	if (kind === "SLACK") {
		return "/w/$workspaceSlug/admin/integrations/slack";
	}
	if (kind === "OUTLINE") {
		return "/w/$workspaceSlug/admin/integrations/outline";
	}
	return "/w/$workspaceSlug/admin/integrations/scm";
}

export function ProviderResourceRepairs({
	state,
	onRelease,
	isReleasing = false,
	page = 0,
	totalPages = 1,
	onPageChange,
}: ProviderResourceRepairsProps) {
	const items = state.status === "ready" ? state.items : [];
	const isLoading = state.status === "loading";
	const [selected, setSelected] = useState<ProviderResourceRepair>();
	const selectedItem = items.find(
		(item) => item.repair.id === selected?.id && item.release === "available",
	);
	return (
		<Card>
			<CardHeader>
				<CardTitle>Provider removals</CardTitle>
				<CardDescription>
					Old resources stay blocked while their removal is uncertain. Reconnecting can use a fresh
					resource.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="We could not load provider removals"
						onRetry={state.onRetry}
					/>
				)}
				{isLoading ? (
					<div
						role="status"
						aria-busy="true"
						aria-label="Loading provider removals"
						className="space-y-3"
					>
						<Skeleton className="h-20" />
						<Skeleton className="h-20" />
					</div>
				) : (
					<>
						{items.length === 0 && state.status === "ready" && (
							<p className="text-sm text-muted-foreground">No pending provider removals.</p>
						)}
						{items.map(({ repair, release }) => (
							<Alert key={repair.id} variant="warning">
								<ShieldAlertIcon />
								<AlertTitle>{RESOURCE_LABELS[repair.resourceType]}</AlertTitle>
								<AlertDescription className="space-y-2">
									<p>{repairStateCopy(repair)}</p>
									{repair.replacementInUse && (
										<p>A connection is using a different resource while this removal stays open.</p>
									)}
									<p className="font-mono text-xs break-all">
										{[repair.origin, repair.parentId, repair.resourceId]
											.filter(Boolean)
											.join(" · ")}
									</p>
									{repair.resourceType === "BOT_USER" && (
										<p>
											A replacement token for the same bot user is also blocked. Token revocation
											can affect that bot’s memberships.
										</p>
									)}
									{repair.owners.map((owner) => (
										<Link
											key={`${owner.workspaceSlug}/${owner.connectionId}`}
											to={owningPage(repair.kind)}
											params={{ workspaceSlug: owner.workspaceSlug }}
											search={{ repairConnection: owner.connectionId }}
											className="block underline"
										>
											Open connection administration
										</Link>
									))}
									{release === "available" && onRelease ? (
										<Button variant="outline" size="sm" onClick={() => setSelected(repair)}>
											Confirm removal
										</Button>
									) : (
										<p>
											{
												RELEASE_UNAVAILABLE_COPY[
													release === "available" ? "instance-admin" : release
												]
											}
										</p>
									)}
								</AlertDescription>
							</Alert>
						))}
					</>
				)}
				{totalPages > 1 && onPageChange && (
					<div className="flex items-center justify-between gap-3">
						<Button
							variant="outline"
							disabled={page === 0 || isLoading}
							onClick={() => onPageChange(page - 1)}
						>
							Previous
						</Button>
						<p className="text-sm text-muted-foreground">
							Page {page + 1} of {totalPages}
						</p>
						<Button
							variant="outline"
							disabled={page + 1 >= totalPages || isLoading}
							onClick={() => onPageChange(page + 1)}
						>
							Next
						</Button>
					</div>
				)}
			</CardContent>
			{selectedItem && onRelease && (
				<ReleaseConfirmation
					key={selectedItem.repair.id}
					repair={selectedItem.repair}
					pending={isReleasing}
					onClose={() => setSelected(undefined)}
					onRelease={onRelease}
				/>
			)}
		</Card>
	);
}

function ReleaseConfirmation({
	repair,
	pending,
	onClose,
	onRelease,
}: {
	repair: ProviderResourceRepair;
	pending: boolean;
	onClose: () => void;
	onRelease: (repair: ProviderResourceRepair) => Promise<void>;
}) {
	const checkboxId = useId();
	const [confirmed, setConfirmed] = useState(false);
	const [error, setError] = useState<unknown>();
	async function release() {
		setError(undefined);
		try {
			await onRelease(repair);
			onClose();
		} catch (error_) {
			setError(error_);
		}
	}
	return (
		<Dialog
			open
			onOpenChange={(open) => {
				if (!open && !pending) {
					onClose();
				}
			}}
		>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Allow this resource to be reused?</DialogTitle>
					<DialogDescription>
						Confirm at the provider that the old resource is gone and its removal can no longer
						affect a replacement. Releasing this block allows a connection to use that identity
						again.
					</DialogDescription>
				</DialogHeader>
				<div className="space-y-4">
					<p className="font-mono text-xs break-all">
						{[repair.origin, repair.parentId, repair.resourceId].filter(Boolean).join(" · ")}
					</p>
					<Field orientation="horizontal">
						<Checkbox
							id={checkboxId}
							checked={confirmed}
							onCheckedChange={setConfirmed}
							disabled={pending}
						/>
						<FieldLabel htmlFor={checkboxId}>
							I confirmed that the old resource is gone and its removal is complete.
						</FieldLabel>
					</Field>
					{error !== undefined && (
						<QueryErrorAlert error={error} title="We could not release this block" />
					)}
				</div>
				<DialogFooter>
					<Button variant="outline" onClick={onClose} disabled={pending}>
						Cancel
					</Button>
					<Button
						onClick={() => {
							void release();
						}}
						disabled={!confirmed || pending}
						aria-busy={pending}
					>
						{pending && <Spinner />}Release block
					</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}
