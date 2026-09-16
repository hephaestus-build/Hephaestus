import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MonitorIcon } from "lucide-react";
import { toast } from "sonner";

import {
	listSessionsOptions,
	listSessionsQueryKey,
	revokeOtherSessionsMutation,
	revokeSessionMutation,
} from "@/api/@tanstack/react-query.gen";
import type { SessionView } from "@/api/types.gen";
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
import { Spinner } from "@/components/ui/spinner";
import { asDate } from "@/lib/dates";
import { hasText } from "@/lib/text";

function formatTimestamp(value?: Date): string | undefined {
	const date = asDate(value);
	if (!date) {
		return undefined;
	}
	return date.toLocaleString(undefined, {
		dateStyle: "medium",
		timeStyle: "short",
	});
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
	const os = /Windows/u.test(ua)
		? "Windows"
		: /iPhone|iPad|iPod/u.test(ua)
			? "iOS"
			: /Mac OS X|Macintosh/u.test(ua)
				? "macOS"
				: /Android/u.test(ua)
					? "Android"
					: /CrOS/u.test(ua)
						? "ChromeOS"
						: /Linux/u.test(ua)
							? "Linux"
							: undefined;
	const browser = /Edg\//u.test(ua)
		? "Edge"
		: /OPR\/|Opera/u.test(ua)
			? "Opera"
			: /Firefox\//u.test(ua)
				? "Firefox"
				: /Chrome\//u.test(ua)
					? "Chrome"
					: /Safari\//u.test(ua)
						? "Safari"
						: undefined;
	if (browser && os) {
		return `${browser} on ${os}`;
	}
	if (browser) {
		return browser;
	}
	if (os) {
		return os;
	}
	return ua;
}

/**
 * Settings section listing the account's active sessions (ADR 0017 native auth).
 *
 * The current session is badged and cannot be revoked from here; other sessions
 * can be revoked individually or all at once via "Sign out everywhere else".
 */
export function SessionsSection() {
	const queryClient = useQueryClient();

	const sessionsQuery = useQuery({ ...listSessionsOptions({}) });

	const invalidateSessions = () =>
		queryClient.invalidateQueries({ queryKey: listSessionsQueryKey() });

	const revokeOne = useMutation({
		...revokeSessionMutation(),
		onSuccess: () => {
			void invalidateSessions();
			toast.success("Session revoked");
		},
		onError: () => {
			toast.error("Failed to revoke session. Please try again later.");
		},
	});

	const revokeOthers = useMutation({
		...revokeOtherSessionsMutation(),
		onSuccess: () => {
			void invalidateSessions();
			toast.success("Signed out of all other sessions");
		},
		onError: () => {
			toast.error("Failed to sign out other sessions. Please try again later.");
		},
	});

	const sessions: SessionView[] = sessionsQuery.data ?? [];
	const otherSessionCount = sessions.filter((s) => s.current !== true).length;

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
				{otherSessionCount > 0 && (
					<AlertDialog>
						<AlertDialogTrigger
							render={
								<Button
									variant="outline"
									size="sm"
									disabled={revokeOthers.isPending}
									className="mt-1 shrink-0"
								>
									{revokeOthers.isPending ? <Spinner className="mr-1.5" /> : null}
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
								<AlertDialogAction
									onClick={() => revokeOthers.mutate({})}
									disabled={revokeOthers.isPending}
								>
									Sign out others
								</AlertDialogAction>
							</AlertDialogFooter>
						</AlertDialogContent>
					</AlertDialog>
				)}
			</div>

			{sessionsQuery.isLoading ? (
				<div className="flex justify-center py-6">
					<Spinner aria-label="Loading sessions" />
				</div>
			) : sessionsQuery.isError ? (
				<p className="text-sm text-destructive" role="alert">
					Failed to load sessions. Please try refreshing the page.
				</p>
			) : sessions.length === 0 ? (
				<p className="text-sm text-muted-foreground">No active sessions found.</p>
			) : (
				<div className="space-y-3" role="list">
					{sessions.map((session) => {
						const signedInAt = formatTimestamp(session.issuedAt);
						const expiresAt = formatTimestamp(session.expiresAt);
						const deviceLabel = describeUserAgent(session.userAgent);
						// Scope the pending state to the row actually being revoked so a single revoke
						// doesn't disable/spin every other session's button.
						const isRevokingThis =
							revokeOne.isPending && revokeOne.variables.path.jti === session.jti;
						return (
							<div
								key={session.jti ?? `${session.userAgent}:${session.ip}`}
								role="listitem"
								aria-label={deviceLabel}
								className="flex items-center justify-between gap-4 rounded-lg border p-4"
							>
								<div className="flex min-w-0 items-center gap-3">
									<MonitorIcon className="size-5 shrink-0" aria-hidden="true" />
									<div className="min-w-0">
										<div className="flex items-center gap-2">
											<span
												className="truncate text-sm font-medium"
												title={session.userAgent ?? undefined}
											>
												{deviceLabel}
											</span>
											{session.current === true && <Badge variant="secondary">This device</Badge>}
										</div>
										<p className="truncate text-xs text-muted-foreground">
											{[
												session.ip,
												hasText(signedInAt) && `signed in ${signedInAt}`,
												hasText(expiresAt) && `expires ${expiresAt}`,
											]
												.filter(Boolean)
												.join(" · ") || "No session details available"}
										</p>
									</div>
								</div>

								{session.current === true ? (
									<Button variant="outline" size="sm" disabled aria-label="Current session">
										Current
									</Button>
								) : (
									<Button
										variant="outline"
										size="sm"
										disabled={isRevokingThis || !hasText(session.jti)}
										onClick={() =>
											hasText(session.jti) && revokeOne.mutate({ path: { jti: session.jti } })
										}
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
			)}
		</section>
	);
}
