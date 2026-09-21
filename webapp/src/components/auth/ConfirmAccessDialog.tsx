import { formatDuration, intervalToDuration } from "date-fns";
import type { ReactNode } from "react";

import type { IdentityProviderView } from "@/api/types.gen";
import { SignInProviderButton } from "@/components/auth/SignInProviderButton";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";

export interface ConfirmAccessDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	/**
	 * Registrations this account can confirm with — the caller has already intersected what the
	 * instance offers with what the account is linked to. Offering anything else resolves or
	 * provisions a *different* account and signs the operator out of their own session.
	 */
	providers: IdentityProviderView[];
	loading: boolean;
	error: boolean;
	/** How recent the sign-in has to be, in whole seconds, when the server named a window. */
	maxAgeSeconds?: number;
	onSignIn: (registrationId: string) => void;
	onRetry: () => void;
}

function signInWindow(maxAgeSeconds: number): string {
	return formatDuration(intervalToDuration({ start: 0, end: maxAgeSeconds * 1000 }));
}

export function ConfirmAccessDialog({
	open,
	onOpenChange,
	providers,
	loading,
	error,
	maxAgeSeconds,
	onSignIn,
	onRetry,
}: ConfirmAccessDialogProps) {
	let body: ReactNode;
	if (loading) {
		body = (
			<>
				<span className="sr-only">Loading sign-in options…</span>
				<Skeleton className="h-9 w-full" />
			</>
		);
	} else if (error) {
		body = (
			<>
				<p role="alert">Could not load sign-in options.</p>
				<Button variant="outline" onClick={onRetry}>
					Try again
				</Button>
			</>
		);
	} else if (providers.length === 0) {
		body = <p>No supported sign-in provider is available. Contact your instance operator.</p>;
	} else {
		body = providers.map((provider) => (
			<SignInProviderButton
				key={provider.registrationId ?? provider.displayName}
				provider={provider}
				onSignIn={onSignIn}
			/>
		));
	}

	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Confirm access</DialogTitle>
					<DialogDescription>
						{maxAgeSeconds === undefined
							? "This action needs a recent sign-in. "
							: `This action needs a sign-in from the last ${signInWindow(maxAgeSeconds)}. `}
						Sign in again with an identity already linked to your account, then retry the action.
						Your provider may sign you straight back in without asking for anything, which is
						expected: this refreshes when you last signed in, and is not a second factor.
					</DialogDescription>
				</DialogHeader>
				<DialogBody className="flex flex-col gap-2" aria-busy={loading}>
					{body}
				</DialogBody>
			</DialogContent>
		</Dialog>
	);
}
