import { cn } from "cn";
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

export interface ImpersonationBannerProps {
	/** Whose account the operator is acting as. */
	displayName: string;
	writesEnabled: boolean;
	/** The exit request is in flight. */
	isExiting?: boolean;
	onEnableWrites: () => void;
	onDisableWrites: () => void;
	onExit: () => void;
}

export function ImpersonationBanner({
	displayName,
	writesEnabled,
	isExiting = false,
	onEnableWrites,
	onDisableWrites,
	onExit,
}: ImpersonationBannerProps) {
	return (
		<div
			className={cn(
				"sticky top-0 z-50 flex w-full flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b px-4 py-2 text-sm",
				writesEnabled
					? "border-destructive/40 bg-destructive/10 text-destructive"
					: "border-warning/40 bg-warning/10 text-warning",
			)}
		>
			<span>
				Impersonating <strong className="font-semibold">{displayName}</strong>
				<span className="mx-2 opacity-60">·</span>
				{writesEnabled ? <strong className="font-semibold">writes enabled</strong> : "read-only"}
			</span>

			{writesEnabled ? (
				<Button variant="destructive-outline" size="sm" onClick={onDisableWrites}>
					Disable writes
				</Button>
			) : (
				<AlertDialog>
					<AlertDialogTrigger render={<Button variant="warning-outline" size="sm" />}>
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
							<AlertDialogAction variant="destructive" onClick={onEnableWrites}>
								Enable writes
							</AlertDialogAction>
						</AlertDialogFooter>
					</AlertDialogContent>
				</AlertDialog>
			)}

			<Button
				variant={writesEnabled ? "destructive-outline" : "warning-outline"}
				size="sm"
				disabled={isExiting}
				onClick={onExit}
				aria-label="Stop impersonating and restore your account"
			>
				{isExiting ? <Spinner className="mr-2 size-3.5" /> : null}
				Stop impersonating
			</Button>
		</div>
	);
}
