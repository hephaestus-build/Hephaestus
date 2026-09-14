import type { LucideIcon } from "lucide-react";

import type { AdminAccountView } from "@/api/types.gen";
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

export interface ChangeRoleDialogProps {
	/** The user whose role is being changed; `null` keeps the dialog closed. */
	user: AdminAccountView | null;
	icon: LucideIcon;
	isPending: boolean;
	/** Server refusal (e.g. last-admin 409) shown inline; the dialog stays open so it can be read. */
	errorMessage?: string;
	onOpenChange: (open: boolean) => void;
	onConfirm: (user: AdminAccountView, nextRole: string) => void;
}

/**
 * Confirms toggling a single account between USER and APP_ADMIN. Granting APP_ADMIN is an
 * elevation, so it is surfaced as a destructive-styled confirmation; revoking is neutral.
 *
 * The API (`UpdateAccountRequest`) only supports `appRole` today — there are
 * no status or feature-flag fields to expose, so this is intentionally a single binary
 * confirmation rather than a multi-field form.
 */
export function ChangeRoleDialog({
	user,
	icon: Icon,
	isPending,
	errorMessage,
	onOpenChange,
	onConfirm,
}: ChangeRoleDialogProps) {
	const isAdmin = user?.appRole === "APP_ADMIN";
	const nextRole = isAdmin ? "USER" : "APP_ADMIN";
	const granting = nextRole === "APP_ADMIN";
	const name = user?.displayName ?? user?.primaryEmail ?? "this account";

	return (
		<AlertDialog open={user !== null} onOpenChange={onOpenChange}>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogMedia>
						<Icon className={granting ? "text-destructive" : undefined} aria-hidden />
					</AlertDialogMedia>
					<AlertDialogTitle>
						{granting ? "Grant application admin?" : "Revoke application admin?"}
					</AlertDialogTitle>
					<AlertDialogDescription>
						{granting ? (
							<>
								This gives <strong>{name}</strong> full application-admin access, including managing
								other users and read-only user views. Continue?
							</>
						) : (
							<>
								This removes application-admin access from <strong>{name}</strong>. They will become
								a regular user.
							</>
						)}
					</AlertDialogDescription>
				</AlertDialogHeader>
				{errorMessage && (
					<p
						role="alert"
						className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
					>
						{errorMessage}
					</p>
				)}
				<AlertDialogFooter>
					<AlertDialogCancel disabled={isPending}>Cancel</AlertDialogCancel>
					<AlertDialogAction
						variant={granting ? "destructive" : "default"}
						disabled={isPending}
						onClick={() => user && onConfirm(user, nextRole)}
					>
						{isPending ? <Spinner className="size-4" /> : null}
						{granting ? "Grant admin" : "Revoke admin"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
