import { differenceInCalendarDays, format } from "date-fns";
import { CheckIcon, CircleAlertIcon, KeyRoundIcon, TriangleAlertIcon } from "lucide-react";
import { useState } from "react";

import type { OutlineTokenStatus } from "@/api/types.gen";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import {
	CONNECTION_STATE_LABEL,
	type ConnectionState,
} from "@/components/admin/integrations/sync-format";
import { RelativeTime } from "@/components/common/RelativeTime";
import { useNow } from "@/components/common/use-now";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from "@/components/ui/card";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { asDate } from "@/lib/dates";
import { hasText } from "@/lib/text";

export interface OutlineConnectInput {
	serverUrl: string;
	token: string;
}

export interface OutlineConnectCardProps {
	connected: boolean;
	/**
	 * The connection's lifecycle state. Typed to the wire union rather than `string` so the copy for
	 * each state is a lookup the compiler checks, instead of whatever `toLowerCase()` happens to emit.
	 */
	connectionState?: ConnectionState;
	connectionLabel?: string;
	tokenStatus?: OutlineTokenStatus;
	isTokenStatusLoading?: boolean;
	isConnecting?: boolean;
	isDisconnecting?: boolean;
	errorMessage?: string;
	/** This instance has no Outline integration, so the connect form is a dead end and the card says so. */
	connectUnavailable?: boolean;
	onConnect: (input: OutlineConnectInput) => void;
	onDisconnect: () => void;
}

// Client-side format hint only — the server re-validates the URL through the SSRF guard on connect.
const HTTPS_URL = /^https:\/\/.+/i;
const CLOUD_SERVER_URL = "https://app.getoutline.com";

/** Inside this window the admin has to act: Outline keys cannot be rotated through the API. */
const EXPIRY_WARNING_DAYS = 14;

/**
 * The Outline lifecycle card — the one piece with no SCM/Slack analogue, because Outline is an
 * API-token paste rather than an OAuth install.
 *
 * When disconnected it is the connect form (server URL + API token). When connected it names the
 * linked instance and shows the stored token's health plus a guarded disconnect; the connection plane
 * itself — health, freshness, webhook diagnostics, running-job progress and the Sync/Cancel controls —
 * lives in the shared `SyncStatusHeader` above this card, and which collections are mirrored is
 * managed in `OutlineCollectionsSection` below it. Pure presentation.
 */
export function OutlineConnectCard({
	connected,
	connectionState,
	connectionLabel,
	tokenStatus,
	isTokenStatusLoading = false,
	isConnecting = false,
	isDisconnecting = false,
	errorMessage,
	connectUnavailable = false,
	onConnect,
	onDisconnect,
}: OutlineConnectCardProps) {
	const [serverUrl, setServerUrl] = useState("");
	const [token, setToken] = useState("");
	// An absent state predates the catalog's state field; treat it as active rather than invent a fault.
	const isConnectionActive = connectionState == null || connectionState === "ACTIVE";
	const [disconnectOpen, setDisconnectOpen] = useState(false);

	const serverUrlInvalid = serverUrl.length > 0 && !HTTPS_URL.test(serverUrl.trim());
	const canConnect = HTTPS_URL.test(serverUrl.trim()) && token.trim().length > 0 && !isConnecting;

	return (
		<div className="space-y-6">
			<Card>
				<CardHeader>
					<IntegrationCardHeading>
						{connected ? "Outline connection" : "Connect Outline"}
					</IntegrationCardHeading>
					{!connected && (
						<CardDescription>
							Mirror Outline collections so their design docs and decision records reach practice
							reviews as context. Use a dedicated bot-user API token; after connecting you choose
							exactly which collections are mirrored.
						</CardDescription>
					)}
				</CardHeader>

				<CardContent className="space-y-4">
					{!connected ? (
						<FieldGroup>
							<Field data-invalid={serverUrlInvalid}>
								<FieldLabel htmlFor="outline-server-url">Server URL</FieldLabel>
								<Input
									id="outline-server-url"
									value={serverUrl}
									disabled={isConnecting}
									onChange={(e) => setServerUrl(e.target.value)}
									// Placeholder, never a default value: a prefilled cloud URL would ship a
									// self-hoster's token to Outline Cloud if they only pasted the token.
									placeholder={CLOUD_SERVER_URL}
									autoComplete="off"
									aria-invalid={serverUrlInvalid}
								/>
								<FieldDescription>
									Your Outline host — <code>{CLOUD_SERVER_URL}</code> for Outline Cloud, otherwise
									your self-hosted URL.
								</FieldDescription>
								{serverUrlInvalid && <FieldError>Enter an https:// URL.</FieldError>}
							</Field>

							<Field>
								<FieldLabel htmlFor="outline-token">API token</FieldLabel>
								<Input
									id="outline-token"
									type="password"
									value={token}
									disabled={isConnecting}
									onChange={(e) => setToken(e.target.value)}
									placeholder="ol_api_…"
									autoComplete="off"
								/>
								<FieldDescription>
									Create it in Outline under <em>Settings → API Keys</em>. A dedicated bot-user
									token is recommended.
								</FieldDescription>
							</Field>

							{errorMessage && <FieldError>{errorMessage}</FieldError>}

							{connectUnavailable && (
								<Alert variant="warning">
									<TriangleAlertIcon />
									<AlertTitle>Outline may not be enabled on this instance</AlertTitle>
									<AlertDescription>
										The server has no Outline integration configured, so connecting cannot succeed
										here. If your URL and token are correct, ask your server administrator to enable
										the Outline integration for this deployment.
									</AlertDescription>
								</Alert>
							)}

							<Button
								onClick={() => onConnect({ serverUrl: serverUrl.trim(), token: token.trim() })}
								disabled={!canConnect}
								className="w-full"
							>
								{isConnecting && <Spinner />}
								{isConnecting ? "Connecting…" : "Connect Outline"}
							</Button>
						</FieldGroup>
					) : (
						<>
							{/* Which Outline instance is linked — the one fact the connection plane above doesn't
							    carry. The green check is a claim that syncing works, so it is spent only on ACTIVE;
							    any other state is explained by the shared notice above this card. */}
							<div className="flex items-center gap-2 text-sm">
								{isConnectionActive ? (
									<CheckIcon className="size-4 text-success" aria-hidden />
								) : (
									<CircleAlertIcon className="size-4 text-muted-foreground" aria-hidden />
								)}
								<span>
									Outline {connectionState ? CONNECTION_STATE_LABEL[connectionState] : "connected"}
									{connectionLabel ? ` — ${connectionLabel}` : ""}
								</span>
							</div>

							<OutlineTokenPanel tokenStatus={tokenStatus} isLoading={isTokenStatusLoading} />
						</>
					)}
				</CardContent>

				{connected && (
					// Disconnect is a destructive lifecycle action, so it stands alone in its own footer —
					// never on the same row as Sync-now, which lives in the header above.
					<CardFooter className="justify-end">
						<Button
							variant="destructive-outline"
							size="sm"
							onClick={() => setDisconnectOpen(true)}
							disabled={isDisconnecting}
						>
							{isDisconnecting ? "Disconnecting…" : "Disconnect Outline…"}
						</Button>
					</CardFooter>
				)}
			</Card>

			<AlertDialog open={disconnectOpen} onOpenChange={setDisconnectOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Disconnect Outline?</AlertDialogTitle>
						<AlertDialogDescription>
							The document mirror stops syncing and every mirrored document for this workspace is
							erased. Documents in Outline itself are not affected. You can reconnect later with a
							token.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={isDisconnecting}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={isDisconnecting}
							onClick={() => {
								setDisconnectOpen(false);
								onDisconnect();
							}}
						>
							{isDisconnecting ? "Disconnecting…" : "Disconnect"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</div>
	);
}

interface OutlineTokenPanelProps {
	tokenStatus?: OutlineTokenStatus;
	isLoading: boolean;
}

/**
 * The state of the stored API key. Outline keys cannot rotate themselves through the API, so the most this can do
 * is warn early. Rejection and imminent expiry both silently kill the mirror, so both are alerts, not muted text.
 */
function OutlineTokenPanel({ tokenStatus, isLoading }: OutlineTokenPanelProps) {
	const now = useNow();
	if (isLoading) {
		return <Skeleton className="h-5 w-64" />;
	}
	if (!tokenStatus) {
		return null;
	}

	if (!tokenStatus.accepted) {
		return (
			<Alert variant="destructive">
				<TriangleAlertIcon />
				<AlertTitle>Outline no longer accepts this token — reconnect with a new one</AlertTitle>
				<AlertDescription>
					Syncing is stopped until a working API key is stored. Create a key in Outline under{" "}
					<strong>Settings → API Keys</strong>, then disconnect and reconnect here with it.
				</AlertDescription>
			</Alert>
		);
	}

	const expiresAt = asDate(tokenStatus.expiresAt);
	const lastActiveAt = asDate(tokenStatus.lastActiveAt);
	// A scoped key (or one owned by a user who cannot see it) cannot list itself, so Outline accepts
	// the token while telling us nothing about it. Say only what we know.
	const hasMetadata =
		tokenStatus.name != null ||
		tokenStatus.last4 != null ||
		expiresAt != null ||
		lastActiveAt != null;

	const daysLeft = expiresAt ? differenceInCalendarDays(expiresAt, new Date(now)) : undefined;
	const expiringSoon = daysLeft != null && daysLeft <= EXPIRY_WARNING_DAYS;

	return (
		<div className="space-y-2">
			<div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
				<span className="flex items-center gap-1.5">
					<KeyRoundIcon className="size-4 text-success" aria-hidden />
					Outline accepts this token
				</span>
				{hasMetadata && (hasText(tokenStatus.name) || hasText(tokenStatus.last4)) && (
					<span>
						{tokenStatus.name ?? "API key"}
						{tokenStatus.last4 ? ` (…${tokenStatus.last4})` : ""}
					</span>
				)}
				{lastActiveAt && (
					<span>
						Last used <RelativeTime value={lastActiveAt} />
					</span>
				)}
				{hasMetadata && !expiresAt && <span>Never expires</span>}
				{expiresAt && !expiringSoon && (
					<span>
						Expires in {daysLeft} day{daysLeft === 1 ? "" : "s"} (on{" "}
						{format(expiresAt, "d MMM yyyy")})
					</span>
				)}
			</div>

			{expiresAt && expiringSoon && (
				<Alert variant="warning">
					<TriangleAlertIcon />
					<AlertTitle>
						{daysLeft <= 0
							? `This API key expired on ${format(expiresAt, "d MMM yyyy")}`
							: `This API key expires in ${daysLeft} day${daysLeft === 1 ? "" : "s"} (on ${format(expiresAt, "d MMM yyyy")})`}
					</AlertTitle>
					<AlertDescription>
						Outline API keys cannot be rotated through the API, so create a fresh key in Outline
						under <strong>Settings → API Keys</strong> and re-enter it here before this one lapses —
						the mirror stops the moment it does.
					</AlertDescription>
				</Alert>
			)}
		</div>
	);
}
