import { CircleAlertIcon, CircleCheckIcon, CircleDashedIcon, CircleSlashIcon } from "lucide-react";

import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { Button } from "~/components/common/Button";
import { ExternalLink } from "~/components/common/ExternalLink";
import { Notice } from "~/components/common/Notice";
import { Skeleton } from "~/components/common/Skeleton";
import { Spinner } from "~/components/common/Spinner";
import type { SiteAccessEntry } from "~/shared/rpc";

export type SiteAccessState =
	| { status: "loading" }
	| { status: "error"; message: string; onRetry: () => void }
	| { status: "ready"; entries: SiteAccessEntry[] };

/** What became of the last grant or removal the reader started, for the one site it was about. */
export type SiteActivity =
	| { origin: string; status: "pending" }
	| { origin: string; status: "denied" }
	| { origin: string; status: "error"; message: string };

export interface SiteAccessListProps {
	state: SiteAccessState;
	activity?: SiteActivity;
	/** Where the reader manages their workspaces, for the empty state; nothing else links there. */
	webAppOrigin: string;
	/** Called from the click itself: Chrome grants access only inside a user gesture. */
	onGrant: (origin: string) => void;
	onRevoke: (origin: string) => void;
}

function host(origin: string): string {
	try {
		return new URL(origin).host;
	} catch {
		return origin;
	}
}

function SiteRow({
	entry,
	activity,
	onGrant,
	onRevoke,
}: {
	entry: SiteAccessEntry;
	activity: SiteActivity | undefined;
	onGrant: (origin: string) => void;
	onRevoke: (origin: string) => void;
}) {
	const pending = activity?.status === "pending";
	const provider = entry.providerType === "GITHUB" ? "GitHub" : "GitLab";
	const ProviderIcon = entry.providerType === "GITHUB" ? GithubIcon : GitlabIcon;
	return (
		<li className="flex flex-col gap-2 p-3.5">
			<div className="flex flex-wrap items-center gap-x-3 gap-y-2">
				<span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-foreground">
					<ProviderIcon aria-hidden="true" aria-label={provider} className="size-4" />
				</span>
				<div className="flex min-w-0 flex-1 flex-col">
					<p className="text-sm font-medium break-all">{host(entry.origin)}</p>
					<p className="text-xs text-muted-foreground">
						{provider} · used by {entry.workspaces.join(", ")}
					</p>
				</div>
				<div className="flex items-center gap-2">
					{entry.granted ? (
						<span className="flex items-center gap-1 text-xs font-medium text-success">
							<CircleCheckIcon aria-hidden className="size-3.5" />
							Allowed
						</span>
					) : (
						<span className="flex items-center gap-1 text-xs text-muted-foreground">
							<CircleDashedIcon aria-hidden className="size-3.5" />
							Not allowed
						</span>
					)}
					{entry.granted ? (
						<Button
							variant="outline"
							size="sm"
							disabled={pending}
							aria-label={`Remove access to ${entry.origin}`}
							onClick={() => onRevoke(entry.origin)}
						>
							{pending ? <Spinner /> : null}
							Remove
						</Button>
					) : (
						<Button
							variant="mentor"
							size="sm"
							disabled={pending}
							aria-label={`Allow on ${entry.origin}`}
							onClick={() => onGrant(entry.origin)}
						>
							{pending ? <Spinner /> : null}
							Allow
						</Button>
					)}
				</div>
			</div>
			{activity?.status === "denied" ? (
				<p className="flex items-start gap-1.5 text-xs text-muted-foreground" role="status">
					<CircleSlashIcon aria-hidden className="mt-px size-3.5 shrink-0" />
					Chrome did not allow access, so nothing changed. Choose Allow again to see the prompt.
				</p>
			) : null}
			{activity?.status === "error" ? (
				<p className="flex items-start gap-1.5 text-xs text-destructive" role="alert">
					<CircleAlertIcon aria-hidden className="mt-px size-3.5 shrink-0" />
					{activity.message}
				</p>
			) : null}
		</li>
	);
}

/**
 * The provider sites the reader's workspaces are connected to, each with its own grant. The
 * extension runs on a site only after this click, and stops the moment access is removed.
 */
export function SiteAccessList({
	state,
	activity,
	webAppOrigin,
	onGrant,
	onRevoke,
}: SiteAccessListProps) {
	if (state.status === "loading") {
		return (
			<div className="flex flex-col gap-2" aria-hidden>
				<Skeleton className="h-14 w-full" />
				<Skeleton className="h-14 w-full" />
			</div>
		);
	}
	if (state.status === "error") {
		return (
			<Notice
				icon={CircleAlertIcon}
				tone="destructive"
				title="Sites could not be loaded"
				action={
					<Button variant="outline" size="sm" onClick={state.onRetry}>
						Try again
					</Button>
				}
			>
				{state.message}
			</Notice>
		);
	}
	if (state.entries.length === 0) {
		return (
			<Notice
				icon={CircleDashedIcon}
				title="No GitHub or GitLab site to allow yet"
				action={
					<ExternalLink href={webAppOrigin} allowedOrigin={webAppOrigin} button={{ size: "sm" }}>
						Open Hephaestus
					</ExternalLink>
				}
			>
				Sites appear here once you belong to a Hephaestus workspace that is connected to GitHub or
				GitLab. Open Hephaestus to see your workspaces or to ask to join one.
			</Notice>
		);
	}
	return (
		<ul className="flex flex-col divide-y divide-border rounded-lg border border-border">
			{state.entries.map((entry) => (
				<SiteRow
					key={entry.origin}
					entry={entry}
					activity={activity?.origin === entry.origin ? activity : undefined}
					onGrant={onGrant}
					onRevoke={onRevoke}
				/>
			))}
		</ul>
	);
}
