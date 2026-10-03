import { MonitorIcon, PuzzleIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { SessionView } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { useNow } from "@/components/common/use-now";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { asDate, formatDayTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

const OS_TOKENS: readonly (readonly [RegExp, string])[] = [
	[/Windows/u, "Windows"],
	[/iPhone|iPad|iPod/u, "iOS"],
	[/Mac OS X|Macintosh/u, "macOS"],
	[/Android/u, "Android"],
	[/CrOS/u, "ChromeOS"],
	[/Linux/u, "Linux"],
];

const BROWSER_TOKENS: readonly (readonly [RegExp, string])[] = [
	[/Edg\//u, "Edge"],
	[/OPR\/|Opera/u, "Opera"],
	[/Firefox\//u, "Firefox"],
	[/Chrome\//u, "Chrome"],
	[/Safari\//u, "Safari"],
];

function firstMatch(tokens: readonly (readonly [RegExp, string])[], ua: string) {
	return tokens.find(([pattern]) => pattern.test(ua))?.[1];
}

/**
 * Best-effort "Browser on OS" label from a raw User-Agent string. A raw UA is unreadable to humans,
 * so a user can't tell their sessions apart — the recognition the revoke feature depends on. The raw
 * string is kept as a tooltip for the rare case the heuristic misses. Order matters (Edge/Opera
 * before Chrome; Chrome before Safari) because UAs nest these tokens.
 */
function describeUserAgent(ua?: string): string {
	if (!hasText(ua)) {
		return "Unknown device";
	}
	const os = firstMatch(OS_TOKENS, ua);
	const browser = firstMatch(BROWSER_TOKENS, ua);
	if (browser !== undefined && os !== undefined) {
		return `${browser} on ${os}`;
	}
	if (browser !== undefined) {
		return browser;
	}
	if (os !== undefined) {
		return os;
	}
	return ua;
}

/**
 * The label a person recognises a session by. A browser extension signs in on its own, beside the
 * browser tab it lives in, so its row says so rather than repeating that browser's label.
 */
function describeSession(session: SessionView): string {
	const device = describeUserAgent(session.userAgent);
	return session.client === "BROWSER_EXTENSION" ? `Browser extension in ${device}` : device;
}

export type SessionsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			sessions: SessionView[];
			/** The session being revoked, which shows a spinner and blocks a repeat click. */
			revokingJti: string | null;
			revokingOthers: boolean;
			onRevoke: (jti: string) => void;
			onRevokeOthers: () => void;
	  };

export interface SessionsSectionProps {
	state: SessionsState;
}

export function SessionsSection({ state }: SessionsSectionProps) {
	const today = new Date(useNow());
	const sessions = state.status === "ready" ? state.sessions : [];
	const otherSessionCount = sessions.filter((s) => !s.current).length;

	let body: ReactNode;
	if (state.status === "loading") {
		body = (
			<div className="space-y-3" role="list" aria-busy="true" aria-label="Loading sessions">
				{Array.from({ length: 2 }, (_, index) => (
					<div
						key={index}
						role="listitem"
						className="flex items-center justify-between gap-4 rounded-lg border p-4"
					>
						<div className="flex min-w-0 items-center gap-3">
							<Skeleton className="size-5 shrink-0" />
							<div className="space-y-1.5">
								<Skeleton className="h-4 w-40" />
								<Skeleton className="h-3 w-56" />
							</div>
						</div>
						<Skeleton className="h-7 w-16" />
					</div>
				))}
			</div>
		);
	} else if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title="Could not load sessions"
				onRetry={state.onRetry}
			/>
		);
	} else if (sessions.length === 0) {
		body = <p className="text-sm text-muted-foreground">No active sessions found.</p>;
	} else {
		body = (
			<div className="space-y-3" role="list">
				{sessions.map((session) => {
					const issuedAt = asDate(session.issuedAt);
					const expiresAt = asDate(session.expiresAt);
					const deviceLabel = describeSession(session);
					const SessionIcon = session.client === "BROWSER_EXTENSION" ? PuzzleIcon : MonitorIcon;
					const isRevokingThis = state.revokingJti === session.jti;
					return (
						<div
							key={session.jti}
							role="listitem"
							aria-label={deviceLabel}
							className="flex items-center justify-between gap-4 rounded-lg border p-4"
						>
							<div className="flex min-w-0 items-center gap-3">
								<SessionIcon className="size-5 shrink-0" aria-hidden="true" />
								<div className="min-w-0">
									<div className="flex items-center gap-2">
										<span
											className="truncate text-sm font-medium"
											title={session.userAgent ?? undefined}
										>
											{deviceLabel}
										</span>
										{session.current && <Badge variant="secondary">This device</Badge>}
									</div>
									<p className="truncate text-xs text-muted-foreground">
										{[
											session.ip,
											issuedAt && `signed in ${formatDayTime(issuedAt, today)}`,
											expiresAt && `expires ${formatDayTime(expiresAt, today)}`,
										]
											.filter(Boolean)
											.join(" · ") || "No session details available"}
									</p>
								</div>
							</div>

							{session.current ? (
								<Button variant="outline" size="sm" disabled aria-label="Current session">
									Current
								</Button>
							) : (
								<Button
									variant="outline"
									size="sm"
									disabled={isRevokingThis || !hasText(session.jti)}
									onClick={() => {
										if (hasText(session.jti)) {
											state.onRevoke(session.jti);
										}
									}}
									aria-label="Revoke this session"
								>
									{isRevokingThis ? <Spinner className="mr-1.5" /> : null}
									Revoke
								</Button>
							)}
						</div>
					);
				})}
			</div>
		);
	}

	return (
		<section className="space-y-4" aria-labelledby="sessions-heading">
			<div className="flex items-start justify-between gap-4">
				<div className="space-y-1">
					<h2 id="sessions-heading" className="text-xl font-semibold">
						Active Sessions
					</h2>
					<p className="text-sm text-muted-foreground">
						Devices and browsers currently signed in to your account.
					</p>
				</div>
				{state.status === "ready" && otherSessionCount > 0 && (
					<AlertDialog>
						<AlertDialogTrigger
							render={
								<Button
									variant="outline"
									size="sm"
									disabled={state.revokingOthers}
									className="mt-1 shrink-0"
								>
									{state.revokingOthers ? <Spinner className="mr-1.5" /> : null}
									Sign out {otherSessionCount} other{" "}
									{otherSessionCount === 1 ? "session" : "sessions"}
								</Button>
							}
						/>
						<AlertDialogContent>
							<AlertDialogHeader>
								<AlertDialogTitle>
									Sign out of {otherSessionCount} other{" "}
									{otherSessionCount === 1 ? "session" : "sessions"}?
								</AlertDialogTitle>
								<AlertDialogDescription>
									This revokes every session except the one you are using now. The other{" "}
									{otherSessionCount === 1 ? "device" : `${otherSessionCount} devices`} will need to
									sign in again.
								</AlertDialogDescription>
							</AlertDialogHeader>
							<AlertDialogFooter>
								<AlertDialogCancel>Cancel</AlertDialogCancel>
								<AlertDialogAction onClick={state.onRevokeOthers} disabled={state.revokingOthers}>
									Sign out others
								</AlertDialogAction>
							</AlertDialogFooter>
						</AlertDialogContent>
					</AlertDialog>
				)}
			</div>

			{body}
		</section>
	);
}
