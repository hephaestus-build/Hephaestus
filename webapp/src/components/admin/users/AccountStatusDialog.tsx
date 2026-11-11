import type { AdminAccountView } from "@/api/types.gen";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";

export interface AccountStatusDialogProps {
	/** The account to block or unblock. It stays set while the dialog closes, so the words do not change. */
	user: AdminAccountView | null;
	open: boolean;
	pending: boolean;
	onOpenChange: (open: boolean) => void;
	/** Blocks an active account or unblocks a blocked one. */
	onConfirm: (user: AdminAccountView) => void;
}

function actionLabel(blocked: boolean, pending: boolean): string {
	if (blocked) {
		return pending ? "Unblocking…" : "Unblock account";
	}
	return pending ? "Blocking…" : "Block account";
}

/** Asks an instance admin to confirm blocking or unblocking an account. */
export function AccountStatusDialog({
	user,
	open,
	pending,
	onOpenChange,
	onConfirm,
}: AccountStatusDialogProps) {
	const blocked = user?.status === "BLOCKED";
	const name = user?.displayName ?? "this account";
	return (
		<AlertDialog open={open} onOpenChange={onOpenChange}>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogTitle>{blocked ? `Unblock ${name}?` : `Block ${name}?`}</AlertDialogTitle>
					<AlertDialogDescription>
						{blocked
							? "They can sign in again. If the account has a verified email address, Hephaestus tells them by email."
							: "This signs them out everywhere at once, and they cannot sign in until an instance admin unblocks the account. Their workspaces and data stay as they are."}
					</AlertDialogDescription>
				</AlertDialogHeader>
				<AlertDialogFooter>
					<AlertDialogCancel>Cancel</AlertDialogCancel>
					<AlertDialogAction
						variant={blocked ? "default" : "destructive"}
						disabled={pending || user === null}
						onClick={() => {
							if (user !== null) {
								onConfirm(user);
							}
						}}
					>
						{actionLabel(blocked, pending)}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
