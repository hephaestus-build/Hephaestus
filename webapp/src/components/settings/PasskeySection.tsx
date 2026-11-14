import { type ReactNode, useId, useState } from "react";

import type { PasskeyStatus } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { Button } from "@/components/ui/button";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { copyToClipboard } from "@/lib/clipboard";

export type PasskeyAction = "register" | "verify" | "protection" | "remove" | "codes" | "recover";

export interface PasskeySectionProps {
	status?: PasskeyStatus;
	loading?: boolean;
	error?: string;
	pending?: boolean;
	pendingAction?: PasskeyAction;
	pendingCredentialId?: string;
	recoveryCodes?: string[];
	supported: boolean;
	onRetry: () => void;
	onRegister: (label: string) => void;
	onVerify: () => void;
	onProtection: (enabled: boolean) => void;
	onRemove: (id: string) => void;
	onCreateRecoveryCodes: () => void;
	onRecover: (code: string) => void;
}

interface Confirmation {
	title: string;
	description: string;
	/** Names the action, so the button says what pressing it does. */
	confirmLabel: string;
	action: () => void;
}

const PASSKEY_REMOVAL = {
	description:
		"This passkey will stop working. Other sessions will end. Verify a remaining passkey to restore protected access.",
	confirmLabel: "Remove passkey",
};

// The server keeps the recovery codes only while a passkey remains, and admin access that requires a
// passkey stays locked until a new one is added and verified.
const LAST_PASSKEY_REMOVAL = {
	description:
		"This is your last passkey. Removing it also deletes your recovery codes and signs you out of your other sessions. Admin access that requires a passkey stays locked until you add and verify a new one.",
	confirmLabel: "Remove last passkey",
};

/**
 * How removing a listed passkey is confirmed, and the id of the reason it stays, if it does. The last
 * passkey stays while recovery or personal protection still depends on it.
 */
function passkeyRemoval(
	{ credentials, recoveryRequired, protectionEnabled, assuranceEnforced }: PasskeyStatus,
	id: string,
): { copy: typeof PASSKEY_REMOVAL; heldBy?: string } {
	const removalCopy = assuranceEnforced
		? PASSKEY_REMOVAL
		: {
				...PASSKEY_REMOVAL,
				description: "This passkey will stop working. Your other sessions will end.",
			};
	const lastRemovalCopy = assuranceEnforced
		? LAST_PASSKEY_REMOVAL
		: {
				...LAST_PASSKEY_REMOVAL,
				description:
					"This is your last passkey. Removing it also deletes your recovery codes and signs you out of your other sessions.",
			};
	if (credentials.length !== 1) {
		return { copy: removalCopy };
	}
	if (recoveryRequired) {
		return { copy: lastRemovalCopy, heldBy: `${id}-recovery-active` };
	}
	if (protectionEnabled) {
		return { copy: lastRemovalCopy, heldBy: `${id}-last` };
	}
	return { copy: lastRemovalCopy };
}

/** The instance policy, scoped as it applies: instance administration and workspace administration differ. */
function passkeyRequirement({
	assuranceEnforced,
	instanceAdminRequired,
	workspaceAdminRequired,
}: PasskeyStatus): string | undefined {
	if (!assuranceEnforced) {
		return "Extra authentication checks are disabled on this instance.";
	}
	if (instanceAdminRequired && workspaceAdminRequired) {
		return "This instance requires one for instance and workspace administration.";
	}
	if (instanceAdminRequired) {
		return "This instance requires one for instance administration.";
	}
	if (workspaceAdminRequired) {
		return "This instance requires one for workspace administration.";
	}
	return undefined;
}

/** Keep each policy's visible consequences together, including its confirmation dialogs. */
function passkeyPolicyCopy(assuranceEnforced: boolean) {
	if (assuranceEnforced) {
		return {
			recovery:
				"Recovery is active. Register and verify a new passkey before you use admin access.",
			protection:
				"Require passkey verification for your admin access. When this is off, any passkey that this instance or a workspace requires is still asked for.",
			recoveryConfirmation:
				"This removes all passkeys and ends your other sessions. Admin access stays blocked until you register and verify a new passkey.",
		};
	}
	return {
		recovery: "Recovery is active.",
		protection:
			"Your preference is saved, but extra authentication checks are disabled on this instance.",
		recoveryConfirmation: "This removes all passkeys and ends your other sessions.",
	};
}

export function PasskeySection({
	status,
	loading = false,
	error,
	pending = false,
	pendingAction,
	pendingCredentialId,
	recoveryCodes,
	supported,
	onRetry,
	onRegister,
	onVerify,
	onProtection,
	onRemove,
	onCreateRecoveryCodes,
	onRecover,
}: PasskeySectionProps) {
	const id = useId();

	const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
	// The dialog keeps showing its last subject while it closes, so its button keeps that label too.
	const [confirmLabel, setConfirmLabel] = useState("");
	const ask = (next: Confirmation) => {
		setConfirmLabel(next.confirmLabel);
		setConfirmation(next);
	};
	const [label, setLabel] = useState("");
	const [recoveryCode, setRecoveryCode] = useState("");
	let content: ReactNode;
	if (loading) {
		content = (
			<div className="space-y-3">
				<Skeleton className="h-5 w-64" />
				<Skeleton className="h-10 w-full" />
				<Skeleton className="h-10 w-40" />
			</div>
		);
	} else if (status === undefined) {
		content = (
			<>
				<p role="alert">{error ?? "We could not load your passkeys. Try again."}</p>
				<Button variant="outline" onClick={onRetry}>
					Retry
				</Button>
			</>
		);
	} else {
		const { credentials } = status;
		const policyCopy = passkeyPolicyCopy(status.assuranceEnforced);
		// The server asks for a recent confirmation before any change to an enrolled account: adding
		// another passkey, removing one, protection and recovery codes. A first passkey needs none.
		const locked = status.assuranceEnforced && credentials.length > 0 && !status.verified;
		const lockedReasonId = locked ? `${id}-locked` : undefined;
		// Every change to the passkeys waits for the one in flight and for a recent confirmation.
		const unavailable = pending || locked;
		const removal = passkeyRemoval(status, id);
		const removalReasons = [lockedReasonId, removal.heldBy].filter(
			(reason) => reason !== undefined,
		);
		let verificationStatus = "";
		if (pending && pendingAction === "protection") {
			verificationStatus = "Saving personal protection…";
		} else if (status.verified) {
			// `verified` is a recent check, fresh enough for sensitive actions, not a session-long grant.
			verificationStatus = "Recently confirmed.";
		}
		const requirement = passkeyRequirement(status);
		content = (
			<>
				<p className="text-sm text-muted-foreground">
					A passkey confirms it’s you before administration.
					{requirement !== undefined && ` ${requirement}`}
				</p>
				{status.recoveryRequired && (
					<p id={`${id}-recovery-active`} role="alert">
						{policyCopy.recovery}
					</p>
				)}
				{locked && (
					<div className="flex flex-col items-start gap-3 rounded-lg border p-4 sm:flex-row sm:items-center sm:gap-4">
						<p id={lockedReasonId} className="min-w-0 flex-1 text-sm">
							Confirm it’s you with one of your passkeys before you add or remove a passkey, change
							protection or create recovery codes.
						</p>
						<Button
							pending={pending && pendingAction === "verify"}
							disabled={pending || !supported}
							focusableWhenDisabled
							className="aria-disabled:opacity-50"
							aria-busy={pending && pendingAction === "verify"}
							onClick={onVerify}
						>
							{pending && pendingAction === "verify" ? "Verifying passkey…" : "Verify passkey"}
						</Button>
					</div>
				)}
				{credentials.length === 0 ? (
					<p>No passkeys yet. Register a passkey to protect admin access.</p>
				) : (
					<ul className="space-y-2">
						{credentials.map((credential) => (
							<li key={credential.id} className="flex items-center justify-between gap-4">
								<span className="min-w-0 break-words">{credential.label}</span>
								<Button
									variant="outline"
									aria-label={`Remove ${credential.label}`}
									disabled={unavailable || removal.heldBy !== undefined}
									focusableWhenDisabled
									className="aria-disabled:opacity-50"
									aria-describedby={
										removalReasons.length > 0 ? removalReasons.join(" ") : undefined
									}
									onClick={() =>
										ask({
											title: `Remove ${credential.label}?`,
											...removal.copy,
											action: () => onRemove(credential.id),
										})
									}
								>
									{pending && pendingAction === "remove" && pendingCredentialId === credential.id
										? "Removing passkey…"
										: "Remove"}
								</Button>
							</li>
						))}
					</ul>
				)}
				{removal.heldBy === `${id}-last` && (
					<p id={removal.heldBy} className="text-sm text-muted-foreground">
						Turn off personal admin protection before you remove your last passkey.
					</p>
				)}
				{!supported && (
					<p role="alert">This browser cannot use passkeys here. Use a current browser on HTTPS.</p>
				)}
				<div className="space-y-2">
					<Label htmlFor={`${id}-label`}>Passkey name</Label>
					<Input
						id={`${id}-label`}
						value={label}
						maxLength={100}
						aria-describedby={`${id}-label-hint`}
						onChange={(event) => setLabel(event.target.value)}
						placeholder="Personal device"
					/>
					<p id={`${id}-label-hint`} className="text-sm text-muted-foreground">
						Optional. Without a name, the passkey shows the date you added it.
					</p>
					<p id={`${id}-register-hint`} className="text-sm text-muted-foreground">
						Adding a passkey turns on personal admin protection and signs you out of your other
						sessions.
					</p>
					<Button
						pending={pending && pendingAction === "register"}
						// Once a passkey exists, confirming with it comes first.
						variant={credentials.length > 0 ? "outline" : "default"}
						disabled={unavailable || !supported}
						focusableWhenDisabled
						className="aria-disabled:opacity-50"
						aria-describedby={
							lockedReasonId === undefined
								? `${id}-register-hint`
								: `${lockedReasonId} ${id}-register-hint`
						}
						onClick={() => onRegister(label.trim())}
					>
						{pending && pendingAction === "register" ? "Registering passkey…" : "Register passkey"}
					</Button>
				</div>
				{credentials.length > 0 && (
					<>
						<div className="space-y-2">
							<Button
								variant="outline"
								disabled={unavailable}
								focusableWhenDisabled
								className="aria-disabled:opacity-50"
								aria-describedby={lockedReasonId}
								onClick={() =>
									ask({
										title: "Create new recovery codes?",
										description:
											"Any recovery codes you saved before stop working. Save the new codes before you leave this page.",
										confirmLabel: "Create codes",
										action: onCreateRecoveryCodes,
									})
								}
							>
								{pending && pendingAction === "codes"
									? "Creating recovery codes…"
									: "Create new recovery codes"}
							</Button>
							{recoveryCodes && (
								<div className="space-y-2">
									<p>
										Save these recovery codes somewhere safe. Hephaestus shows them only now. Each
										code works once.
									</p>
									<ul aria-label="Recovery codes">
										{recoveryCodes.map((code) => (
											<li key={code}>
												<code className="break-all">{code}</code>
											</li>
										))}
									</ul>
									<Button
										variant="outline"
										size="sm"
										onClick={() =>
											copyToClipboard(recoveryCodes.join("\n"), "Recovery codes copied.")
										}
									>
										Copy codes
									</Button>
								</div>
							)}
						</div>
						<Field orientation="horizontal">
							<FieldContent>
								<FieldLabel htmlFor={`${id}-protection`}>Personal admin protection</FieldLabel>
								<FieldDescription>{policyCopy.protection}</FieldDescription>
							</FieldContent>
							<Switch
								id={`${id}-protection`}
								checked={status.protectionEnabled}
								disabled={unavailable}
								aria-describedby={lockedReasonId}
								onCheckedChange={onProtection}
							/>
						</Field>
					</>
				)}
				<p role="status" className="text-sm text-muted-foreground">
					{verificationStatus}
				</p>
				<details>
					<summary>Recover passkeys</summary>
					<div className="mt-3 space-y-2">
						<p>
							Recovery removes your passkeys and signs out other sessions. It does not grant admin
							access.
						</p>
						<Label htmlFor={`${id}-recovery`}>Recovery code</Label>
						<Input
							id={`${id}-recovery`}
							type="password"
							value={recoveryCode}
							autoComplete="off"
							onChange={(event) => setRecoveryCode(event.target.value)}
						/>
						<Button
							variant="outline"
							disabled={pending || recoveryCode.trim().length === 0}
							onClick={() => {
								ask({
									title: "Start passkey recovery?",
									description: policyCopy.recoveryConfirmation,
									confirmLabel: "Start recovery",
									action: () => {
										onRecover(recoveryCode.trim());
										setRecoveryCode("");
									},
								});
							}}
						>
							{pending && pendingAction === "recover" ? "Starting recovery…" : "Start recovery"}
						</Button>
					</div>
				</details>
				<p role="alert" aria-atomic="true" className="text-destructive">
					{error}
				</p>
			</>
		);
	}
	return (
		<section aria-busy={loading || pending} aria-labelledby={`${id}-heading`} className="space-y-4">
			<h2 id={`${id}-heading`} className="text-xl font-semibold">
				Passkeys
			</h2>
			{content}
			<ConfirmDialog
				subject={confirmation}
				title={(subject) => subject.title}
				description={(subject) => subject.description}
				confirmLabel={confirmLabel}
				onConfirm={(subject) => subject.action()}
				onClose={() => setConfirmation(null)}
			/>
		</section>
	);
}
