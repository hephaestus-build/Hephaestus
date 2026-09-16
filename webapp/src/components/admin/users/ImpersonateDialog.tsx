import { useId, useState } from "react";

import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

export interface ImpersonationTarget {
	id?: number;
	displayName?: string;
	primaryEmail?: string;
}

export interface ImpersonateDialogProps {
	/** The user to impersonate; `null` keeps the dialog closed. */
	user: ImpersonationTarget | null;
	isPending: boolean;
	/** Optional pre-filled audit reason for a task-specific support entry point. */
	defaultReason?: string;
	/** Server refusal (e.g. admin→admin 409) shown inline; the dialog stays open so it can be read. */
	errorMessage?: string;
	onOpenChange: (open: boolean) => void;
	onConfirm: (user: ImpersonationTarget, reason: string) => void;
}

/**
 * Collects the mandatory audit reason before starting impersonation. The server contract
 * (`ImpersonateRequest`) requires a non-empty `reason`, so the confirm button is
 * disabled until one is provided.
 */
export function ImpersonateDialog({
	user,
	isPending,
	defaultReason = "",
	errorMessage,
	onOpenChange,
	onConfirm,
}: ImpersonateDialogProps) {
	const reasonId = useId();
	const [reason, setReason] = useState(defaultReason);

	// Reset the reason when the dialog targets a (different) user, so a previous entry never
	// leaks into the next impersonation. Done as a render-phase reset (React's recommended
	// "adjusting state on prop change" pattern) rather than an effect.
	const [lastUserId, setLastUserId] = useState<number | null | undefined>(user?.id);
	if (user?.id !== lastUserId) {
		setLastUserId(user?.id);
		setReason(defaultReason);
	}

	const name = user?.displayName ?? user?.primaryEmail ?? "this account";
	const trimmed = reason.trim();
	const canSubmit = trimmed.length > 0 && !isPending;

	return (
		<Dialog open={user !== null} onOpenChange={onOpenChange}>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Impersonate {name}</DialogTitle>
					<DialogDescription>
						You will act as this user until you exit impersonation. This is audited — provide a
						reason.
					</DialogDescription>
				</DialogHeader>
				<form
					onSubmit={(event) => {
						event.preventDefault();
						if (user && canSubmit) {
							onConfirm(user, trimmed);
						}
					}}
					className="space-y-4"
				>
					<div className="space-y-2">
						<Label htmlFor={reasonId}>Reason</Label>
						<Textarea
							id={reasonId}
							required
							value={reason}
							onChange={(event) => setReason(event.target.value)}
							placeholder="e.g. Investigating support ticket #1234"
							// oxlint-disable-next-line jsx-a11y/no-autofocus -- The impersonation dialog cannot be submitted until this required reason is typed.
							autoFocus
						/>
					</div>
					{errorMessage && (
						<p
							role="alert"
							className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
						>
							{errorMessage}
						</p>
					)}
					<DialogFooter>
						<DialogClose render={<Button type="button" variant="outline" disabled={isPending} />}>
							Cancel
						</DialogClose>
						<Button type="submit" disabled={!canSubmit}>
							{isPending ? <Spinner className="size-4" /> : null}
							Impersonate
						</Button>
					</DialogFooter>
				</form>
			</DialogContent>
		</Dialog>
	);
}
