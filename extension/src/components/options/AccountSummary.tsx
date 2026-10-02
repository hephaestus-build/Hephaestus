import { LogOutIcon } from "lucide-react";

import { cn } from "cn";
import { Button } from "~/components/common/Button";
import { formatDateTime } from "~/components/common/format";
import { Spinner } from "~/components/common/Spinner";
import type { AccountSummary as Account } from "~/shared/rpc";

export interface AccountSummaryProps {
	account: Account;
	instanceHost: string;
	/** When the session ends at the latest; Chrome closing ends it sooner. */
	sessionExpiresAt?: string;
	signingOut: boolean;
	onSignOut: () => void;
}

/** Initials rather than the provider avatar: the extension's pages fetch nothing from third parties. */
function initials(account: Account): string {
	const source = account.displayName.trim() === "" ? account.username : account.displayName;
	const letters = source
		.split(/[\s._-]+/u)
		.filter((part) => part !== "")
		.slice(0, 2)
		.map((part) => part[0]?.toUpperCase() ?? "");
	return letters.join("") || "?";
}

function Avatar({ account, className }: { account: Account; className?: string }) {
	return (
		<span
			aria-hidden
			className={cn(
				"flex shrink-0 items-center justify-center rounded-full bg-mentor font-semibold text-mentor-foreground",
				className,
			)}
		>
			{initials(account)}
		</span>
	);
}

/** Who the extension is signed in as, where, and until when — with the way out beside it. */
export function AccountSummary({
	account,
	instanceHost,
	sessionExpiresAt,
	signingOut,
	onSignOut,
}: AccountSummaryProps) {
	const expires = formatDateTime(sessionExpiresAt);
	const name = account.displayName.trim() === "" ? account.username : account.displayName;
	return (
		<div className="flex flex-wrap items-center gap-x-4 gap-y-3">
			<Avatar account={account} className="size-10 text-sm" />
			<div className="flex min-w-0 flex-1 flex-col gap-0.5">
				<p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm font-medium">
					<span className="break-words">{name}</span>
					{account.username === "" ? null : (
						<span className="font-normal text-muted-foreground">@{account.username}</span>
					)}
					{account.instanceAdmin ? (
						<span className="rounded-full border border-border px-2 py-0.5 text-2xs font-medium text-muted-foreground">
							Instance admin
						</span>
					) : null}
				</p>
				<p className="text-xs text-muted-foreground">
					Signed in to {instanceHost}
					{expires === undefined ? "" : ` until ${expires} at the latest, or until Chrome closes`}
				</p>
			</div>
			<Button variant="outline" disabled={signingOut} onClick={onSignOut}>
				{signingOut ? <Spinner /> : <LogOutIcon aria-hidden />}
				{signingOut ? "Signing out…" : "Sign out"}
			</Button>
		</div>
	);
}
