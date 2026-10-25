import { AlertCircle, type LucideIcon } from "lucide-react";

import type { AdminAccountView } from "@/api/types.gen";
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

import { APP_ROLE_LABELS, type AppRole } from "./account-labels";

export interface ChangeRoleDialogProps {
	/** The user whose role is being changed; `null` keeps the dialog closed. */
	user: AdminAccountView | null;
	icon: LucideIcon;
	isPending: boolean;
	/**
	 * Server refusal (e.g. last-admin 409) shown inline while the dialog is open. The dialog stays
	 * dismissible while saving; the page reports a refusal that settles after it closed (ADR 0027).
	 */
	errorMessage?: string;
	onOpenChange: (open: boolean) => void;
	onConfirm: (user: AdminAccountView, nextRole: AppRole) => void;
}

/**
 * Confirms toggling a single account between User and Instance admin. Granting Instance admin is an
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
	const nextRole: AppRole = isAdmin ? "USER" : "APP_ADMIN";
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
						{granting ? "Grant instance admin?" : "Revoke instance admin?"}
					</AlertDialogTitle>
					<AlertDialogDescription>
						{granting ? (
							<>
								<strong>{name}</strong> gets the {APP_ROLE_LABELS[nextRole]} role. It gives full
								access to administer this instance, including managing other users and read-only
								user views.
							</>
						) : (
							<>
								<strong>{name}</strong> goes back to the {APP_ROLE_LABELS[nextRole]} role and loses
								access to administer this instance.
							</>
						)}
					</AlertDialogDescription>
				</AlertDialogHeader>
				{hasText(errorMessage) && (
					<Alert variant="destructive">
						<AlertCircle />
						<AlertDescription>{errorMessage}</AlertDescription>
					</Alert>
				)}
				<AlertDialogFooter>
					<AlertDialogCancel>Cancel</AlertDialogCancel>
					<AlertDialogAction
						variant={granting ? "destructive" : "default"}
						disabled={isPending}
						onClick={() => {
							if (user !== null) {
								onConfirm(user, nextRole);
							}
						}}
					>
						{isPending ? <Spinner className="size-4" /> : null}
						{granting ? "Grant instance admin" : "Revoke instance admin"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
