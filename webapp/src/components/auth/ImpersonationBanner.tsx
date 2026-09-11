import { useMutation } from "@tanstack/react-query";
import { useEffect } from "react";
import { toast } from "sonner";
import { withSessionMutationLock } from "@/integrations/auth/session-mutation";

import { cn } from "cn";
import { exitImpersonationMutation } from "@/api/@tanstack/react-query.gen";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useAuth } from "@/integrations/auth/AuthContext";
import { useImpersonationStore } from "@/stores/impersonation-store";

export function ImpersonationBanner() {
	const { isImpersonating, impersonatedDisplayName } = useAuth();
	const writesEnabled = useImpersonationStore((s) => s.writesEnabled);
	const setWritesEnabled = useImpersonationStore((s) => s.setWritesEnabled);

	const exit = useMutation({
		...withSessionMutationLock(exitImpersonationMutation()),
		onSuccess: () => {
			// Discard impersonated account data before loading the operator session.
			window.location.assign("/");
		},
		onError: () => {
			// The impersonated session may still be active; disable writes until explicitly re-enabled.
			setWritesEnabled(false);
			toast.error("Could not stop impersonating. Please try again.");
		},
	});

	useEffect(() => {
		if (!isImpersonating) {
			setWritesEnabled(false);
			return;
		}
		document.body.setAttribute("data-impersonating", "true");
		return () => {
			document.body.removeAttribute("data-impersonating");
			setWritesEnabled(false);
		};
	}, [isImpersonating, setWritesEnabled]);

	if (!isImpersonating) {
		return null;
	}

	const displayName = impersonatedDisplayName ?? "another account";

	return (
		<div
			role="status"
			aria-live="polite"
			className={cn(
				"sticky top-0 z-50 flex w-full items-center justify-center gap-x-3 gap-y-1 flex-wrap border-b px-4 py-2 text-sm",
				writesEnabled
					? "border-destructive/40 bg-destructive/15 text-destructive"
					: "border-warning/40 bg-warning/15 text-warning",
			)}
		>
			<span>
				Impersonating <strong className="font-semibold">{displayName}</strong>
				<span className="mx-2 opacity-60">·</span>
				{writesEnabled ? <strong className="font-semibold">writes enabled</strong> : "read-only"}
			</span>

			{writesEnabled ? (
				<Button
					variant="outline"
					size="sm"
					onClick={() => setWritesEnabled(false)}
					className="h-7 border-destructive/50 bg-transparent text-destructive hover:bg-destructive/20 hover:text-destructive"
				>
					Disable writes
				</Button>
			) : (
				<AlertDialog>
					<AlertDialogTrigger
						render={
							<Button
								variant="outline"
								size="sm"
								className="h-7 border-warning/50 bg-transparent text-warning hover:bg-warning/20 hover:text-warning"
							/>
						}
					>
						Enable writes
					</AlertDialogTrigger>
					<AlertDialogContent>
						<AlertDialogHeader>
							<AlertDialogTitle>Make changes as {displayName}?</AlertDialogTitle>
							<AlertDialogDescription>
								You are impersonating <strong>{displayName}</strong>. Enabling writes lets you
								create, edit, and delete <em>as this user</em>. Every change is attributed to you in
								the audit log. Writes turn off automatically when you stop impersonating or reload.
							</AlertDialogDescription>
						</AlertDialogHeader>
						<AlertDialogFooter>
							<AlertDialogCancel>Cancel</AlertDialogCancel>
							<AlertDialogAction variant="destructive" onClick={() => setWritesEnabled(true)}>
								Enable writes
							</AlertDialogAction>
						</AlertDialogFooter>
					</AlertDialogContent>
				</AlertDialog>
			)}

			<Button
				variant="outline"
				size="sm"
				disabled={exit.isPending}
				onClick={() => exit.mutate({})}
				aria-label="Stop impersonating and restore your account"
				className={cn(
					"h-7 bg-transparent",
					writesEnabled
						? "border-destructive/50 text-destructive hover:bg-destructive/20 hover:text-destructive"
						: "border-warning/50 text-warning hover:bg-warning/20 hover:text-warning",
				)}
			>
				{exit.isPending ? <Spinner className="mr-2 size-3.5" /> : null}
				Stop impersonating
			</Button>
		</div>
	);
}
