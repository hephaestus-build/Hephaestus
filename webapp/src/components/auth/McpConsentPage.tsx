import { useId } from "react";

import {
	ConfirmAccessDialog,
	type ConfirmAccessDialogProps,
} from "@/components/auth/ConfirmAccessDialog";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { PageLayout } from "@/components/layout/PageLayout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

/** What the consent page knows about the client's request, as the server answers it. */
export interface McpConsentRequestView {
	clientHost: string;
	clientName: string;
	redirectHost: string;
	loopbackRedirect: boolean;
	workspaceName: string;
	scopes: string[];
	refusal?: McpConsentRefusal;
	/** The workspace's admin passkey policy asks the account to confirm with a passkey before it allows. */
	passkeyRequired: boolean;
}

export type McpConsentRefusal =
	| "NOT_ADMIN"
	| "PASSKEY_RECOVERY_PENDING"
	| "INSTANCE_ADMIN_PASSKEY_REQUIRED";

/**
 * The passkey confirmation that Allow runs first when the request needs one: `confirm` asks for the
 * account's passkey and names why the last attempt failed, `add` means the account has none yet, and
 * `unsupported` means this browser cannot use one.
 */
export type McpConsentPasskey =
	| { status: "confirm"; error?: string }
	| { status: "add"; onAdd: () => void }
	| { status: "unsupported" };

export type McpAuthorizationError =
	| "INVALID_CLIENT"
	| "INVALID_CLIENT_METADATA"
	| "INVALID_REDIRECT_URI";

export type McpConsentState =
	| { status: "loading" }
	| { status: "error"; message: string; onRetry: () => void }
	| { status: "gone" }
	| { status: "invalid"; reason: McpAuthorizationError }
	| {
			status: "ready";
			request: McpConsentRequestView;
			/** Present when the request needs a passkey and the account can allow it. */
			passkey?: McpConsentPasskey;
			pending?: "passkey" | "approve" | "refuse";
			onApprove: () => void;
			onRefuse: () => void;
	  };

export interface McpConsentPageProps {
	state: McpConsentState;
	/** Approving grants access, so the server can ask for a recent sign-in first. */
	confirmAccess?: ConfirmAccessDialogProps;
}

const SCOPE_WORDS: Record<string, string> = {
	"practices:read":
		"Read this workspace’s practices, review settings, members’ provider logins and review status.",
	"practices:write":
		"Add, change and remove practices, set feedback modes, choose the repositories and people that Hephaestus reviews, and pause feedback.",
};

const REFUSALS: Record<McpConsentRefusal, string> = {
	NOT_ADMIN: "You are not an admin of this workspace. Only an admin or owner can connect a client.",
	PASSKEY_RECOVERY_PENDING:
		"Your passkey recovery is not finished. Finish it in your passkey settings, then connect the client again.",
	INSTANCE_ADMIN_PASSKEY_REQUIRED:
		"This instance requires a passkey from its instance admins, and an instance admin’s passkey is never lent to a client. Connect the client with an account that is not an instance admin.",
};

const INVALID: Record<McpAuthorizationError, string> = {
	INVALID_CLIENT: "The client did not identify itself with a client metadata document.",
	INVALID_CLIENT_METADATA: "The client’s metadata document could not be read or is not valid.",
	INVALID_REDIRECT_URI:
		"The client asked to send you to an address that its metadata document does not list.",
};

/**
 * The page where an account lets an MCP client act in one workspace. The host comes first because the
 * client cannot choose it; the client's own name is plain text below it.
 */
export function McpConsentPage({ state, confirmAccess }: McpConsentPageProps) {
	const id = useId();
	return (
		<PageLayout className="max-w-xl py-10">
			<HephaestusLogo className="h-8 w-auto" />
			<section
				aria-labelledby={`${id}-heading`}
				aria-busy={state.status === "loading"}
				className="space-y-4"
			>
				<h1 id={`${id}-heading`} className="text-2xl font-semibold">
					Connect an MCP client
				</h1>
				<Body state={state} />
			</section>
			{confirmAccess !== undefined && <ConfirmAccessDialog {...confirmAccess} />}
		</PageLayout>
	);
}

function Body({ state }: Pick<McpConsentPageProps, "state">) {
	switch (state.status) {
		case "loading": {
			return <Skeleton className="h-48 w-full" />;
		}
		case "error": {
			return (
				<Alert variant="destructive">
					<AlertTitle>We could not load this request</AlertTitle>
					<AlertDescription>
						<p>{state.message}</p>
						<Button variant="outline" className="mt-2" onClick={state.onRetry}>
							Retry
						</Button>
					</AlertDescription>
				</Alert>
			);
		}
		case "gone": {
			return (
				<Alert>
					<AlertTitle>This request has expired</AlertTitle>
					<AlertDescription>
						It was answered already, or it waited longer than 10 minutes. Start the client’s sign-in
						again.
					</AlertDescription>
				</Alert>
			);
		}
		case "invalid": {
			return (
				<Alert variant="destructive">
					<AlertTitle>This client cannot connect</AlertTitle>
					<AlertDescription>{INVALID[state.reason]}</AlertDescription>
				</Alert>
			);
		}
		case "ready": {
			return <Request {...state} />;
		}
	}
}

function Request({
	request,
	passkey,
	pending,
	onApprove,
	onRefuse,
}: Extract<McpConsentState, { status: "ready" }>) {
	const { refusal } = request;
	const canAllow = refusal === undefined && (passkey === undefined || passkey.status === "confirm");
	return (
		<div className="space-y-4">
			<div>
				<p className="text-lg font-medium">{request.clientHost}</p>
				<p className="text-sm text-muted-foreground">
					Calls itself <span className="font-medium text-foreground">{request.clientName}</span>
				</p>
			</div>
			<p>
				wants to act for you in the workspace{" "}
				<span className="font-medium">{request.workspaceName}</span>. It may:
			</p>
			<ul className="list-disc space-y-1 pl-5">
				{request.scopes.map((scope) => (
					<li key={scope}>{SCOPE_WORDS[scope] ?? scope}</li>
				))}
			</ul>
			<p className="text-sm text-muted-foreground">
				It can never read source code, observations, feedback, conversations or secrets.
			</p>
			<p className="text-sm text-muted-foreground">
				After you answer, your browser goes to{" "}
				<span className="font-medium">{request.redirectHost}</span>.
			</p>
			{request.loopbackRedirect && (
				<Alert>
					<AlertTitle>The client runs on this computer</AlertTitle>
					<AlertDescription>
						The answer goes to a program on this computer. Allow it only if you started this sign-in
						yourself.
					</AlertDescription>
				</Alert>
			)}
			{refusal !== undefined && (
				<Alert variant="destructive">
					<AlertTitle>You cannot allow this client</AlertTitle>
					<AlertDescription>{REFUSALS[refusal]}</AlertDescription>
				</Alert>
			)}
			{refusal === undefined && passkey !== undefined && (
				<PasskeyStep passkey={passkey} pending={pending === "passkey"} />
			)}
			<div className="flex gap-2">
				{passkey?.status === "add" && (
					<Button onClick={passkey.onAdd} disabled={pending !== undefined}>
						Add a passkey
					</Button>
				)}
				<Button
					onClick={onApprove}
					pending={pending === "passkey" || pending === "approve"}
					disabled={pending !== undefined || !canAllow}
				>
					{passkey === undefined ? "Allow" : "Confirm with passkey and allow"}
				</Button>
				<Button
					variant="outline"
					onClick={onRefuse}
					pending={pending === "refuse"}
					disabled={pending !== undefined}
				>
					Deny
				</Button>
			</div>
		</div>
	);
}

function PasskeyStep({ passkey, pending }: { passkey: McpConsentPasskey; pending: boolean }) {
	return (
		<div className="space-y-2">
			<Alert>
				<AlertTitle>This workspace requires a passkey</AlertTitle>
				<AlertDescription>
					{passkey.status === "add"
						? "Add a passkey to your account first. Then confirm with it to allow this client."
						: "Confirm with your passkey to allow this client."}{" "}
					The client’s access ends one hour after you confirm. Then it asks you again.
				</AlertDescription>
			</Alert>
			{passkey.status === "unsupported" && (
				<Alert variant="destructive">
					<AlertTitle>This browser cannot use passkeys</AlertTitle>
					<AlertDescription>
						Open this page in a current browser on a device that holds one of your passkeys.
					</AlertDescription>
				</Alert>
			)}
			{/* The one announcer of the confirmation: it exists before a message lands in it. */}
			<div aria-live="polite" aria-atomic="true">
				{pending && <p className="text-sm text-muted-foreground">Waiting for your passkey…</p>}
				{!pending && passkey.status === "confirm" && passkey.error !== undefined && (
					<Alert role="none" variant="destructive">
						<AlertTitle>Your passkey did not confirm</AlertTitle>
						<AlertDescription>{passkey.error}</AlertDescription>
					</Alert>
				)}
			</div>
		</div>
	);
}
