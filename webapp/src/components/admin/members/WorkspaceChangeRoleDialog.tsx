import { AlertCircle, ShieldCheck, ShieldOff } from "lucide-react";
import { useState } from "react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogMedia,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";

import type { WorkspaceRoleChange } from "./workspace-members";

export interface WorkspaceChangeRoleDialogProps {
	/** The change waiting for the reader; `null` keeps the dialog closed. */
	change: WorkspaceRoleChange | null;
	isPending: boolean;
	/** The server's refusal, such as the last owner; the dialog stays open so it can be read. */
	errorMessage?: string;
	onOpenChange: (open: boolean) => void;
	onConfirm: (change: WorkspaceRoleChange) => void;
}

function copyFor({ name, from, to, own }: WorkspaceRoleChange) {
	if (to === "OWNER") {
		return {
			title: own ? "Make yourself an owner?" : `Make ${name} an owner?`,
			description: own
				? "You can then delete this workspace and change the role of anyone in it."
				: `${name} can then delete this workspace and change the role of anyone in it, including you.`,
			action: "Make owner",
		};
	}
	if (to === "ADMIN") {
		return {
			title: "Give up your owner role?",
			description:
				"You stay an admin, but you can no longer delete this workspace. Only an owner can make you an owner again.",
			action: "Give up owner role",
		};
	}
	return {
		title: "Remove your admin access?",
		description:
			from === "OWNER"
				? "You become a member and can no longer manage this workspace. Only an owner can make you an owner again."
				: "You become a member and can no longer manage this workspace. Only an admin can give you admin access again.",
		action: "Remove admin access",
	};
}

/**
 * Asks before a workspace role change that is hard to take back: making someone an owner, or
 * changing the reader's own role. `roleChangeAsksFirst` decides which changes reach it.
 *
 * The instance console's `ChangeRoleDialog` toggles the instance admin role of an account, so its
 * copy and its account type are instance-only; this one keeps the same shape for workspace roles.
 */
export function WorkspaceChangeRoleDialog({
	change,
	isPending,
	errorMessage,
	onOpenChange,
	onConfirm,
}: WorkspaceChangeRoleDialogProps) {
	// The content keeps the last change while the dialog animates closed, so the title does not
	// change under the reader.
	const [shown, setShown] = useState(change);
	if (change !== null && change !== shown) {
		setShown(change);
	}
	const copy = shown === null ? null : copyFor(shown);
	const Icon = shown?.to === "OWNER" ? ShieldCheck : ShieldOff;

	return (
		<AlertDialog
			open={change !== null}
			onOpenChange={(open) => {
				if (!open && isPending) {
					return;
				}
				onOpenChange(open);
			}}
		>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogMedia>
						<Icon className="text-destructive" aria-hidden />
					</AlertDialogMedia>
					<AlertDialogTitle>{copy?.title}</AlertDialogTitle>
					<AlertDialogDescription>{copy?.description}</AlertDialogDescription>
				</AlertDialogHeader>
				{hasText(errorMessage) && (
					<Alert variant="destructive">
						<AlertCircle />
						<AlertDescription>{errorMessage}</AlertDescription>
					</Alert>
				)}
				<AlertDialogFooter>
					<AlertDialogCancel disabled={isPending}>Cancel</AlertDialogCancel>
					<AlertDialogAction
						variant="destructive"
						disabled={isPending}
						onClick={() => {
							if (change !== null) {
								onConfirm(change);
							}
						}}
					>
						{isPending ? <Spinner /> : null}
						{copy?.action}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
