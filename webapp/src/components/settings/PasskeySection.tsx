import { type ReactNode, useId, useState } from "react";

import { useSpinDelay } from "spin-delay";

import type { PasskeyStatus } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { Button } from "@/components/ui/button";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

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
	const showSpinner = useSpinDelay(pending, { delay: 1000, minDuration: 500 });

	const [confirmation, setConfirmation] = useState<{
		title: string;
		description: string;
		action: () => void;
	} | null>(null);
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
		let verificationStatus = status.verified
			? "Your passkey was verified. Sensitive actions can require another verification."
			: "Passkey verification is required for protected changes.";
		if (pending && pendingAction === "protection") {
			verificationStatus = "Saving personal protection…";
		}
		content = (
			<>
				<p className="text-sm text-muted-foreground">
					Passkeys protect admin access. Instance and workspace requirements take precedence over
					your personal choice.
				</p>
				{status.instanceAdminRequired && (
					<p className="text-sm">This instance requires passkeys for instance administration.</p>
				)}
				{status.workspaceAdminRequired && (
					<p className="text-sm">
						This instance requires passkeys for all workspace administration.
					</p>
				)}
				{status.recoveryRequired && (
					<p role="alert">
						Recovery is active. Register and verify a new passkey before you use admin access.
					</p>
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
									disabled={pending || !status.verified || credentials.length < 2}
									onClick={() =>
										setConfirmation({
											title: `Remove ${credential.label}?`,
											description:
												"This passkey will stop working. Other sessions will end. Verify a remaining passkey to restore protected access.",
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
				{!supported && (
					<p role="alert">This browser cannot use passkeys here. Use a current browser on HTTPS.</p>
				)}
				<div className="space-y-2">
					<Label htmlFor={`${id}-label`}>Passkey name</Label>
					<Input
						id={`${id}-label`}
						value={label}
						maxLength={100}
						onChange={(event) => setLabel(event.target.value)}
						placeholder="Personal device"
					/>
					<Button
						disabled={pending || !supported || label.trim().length === 0}
						onClick={() => onRegister(label.trim())}
					>
						{showSpinner && pendingAction === "register" && <Spinner />}
						{pending && pendingAction === "register" ? "Registering passkey…" : "Register passkey"}
					</Button>
				</div>
				{credentials.length > 0 && (
					<div className="flex flex-wrap gap-2">
						<Button disabled={pending || !supported} onClick={onVerify}>
							{showSpinner && pendingAction === "verify" && <Spinner />}
							{pending && pendingAction === "verify" ? "Verifying passkey…" : "Verify passkey"}
						</Button>

						<Button
							variant="outline"
							disabled={pending || !status.verified}
							onClick={() =>
								setConfirmation({
									title: "Replace recovery codes?",
									description:
										"All previous recovery codes will stop working. Save the replacement codes before you leave this page.",
									action: onCreateRecoveryCodes,
								})
							}
						>
							{pending && pendingAction === "codes"
								? "Replacing recovery codes…"
								: "Replace recovery codes"}
						</Button>
					</div>
				)}
				{credentials.length > 0 && (
					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor={`${id}-protection`}>Personal admin protection</FieldLabel>
							<FieldDescription>
								Require passkey verification for your admin access. Mandatory instance and workspace
								protection still applies when this is off.
							</FieldDescription>
						</FieldContent>
						<Switch
							id={`${id}-protection`}
							checked={status.protectionEnabled}
							disabled={pending || !status.verified}
							onCheckedChange={onProtection}
						/>
					</Field>
				)}
				<p role="status" className="text-sm text-muted-foreground">
					{verificationStatus}
				</p>
				{credentials.length > 0 && !status.verified && (
					<p className="text-sm text-muted-foreground">
						Verify a passkey before you change protection, remove a passkey, or replace recovery
						codes.
					</p>
				)}
				<p className="text-sm text-muted-foreground">
					Keep a second passkey on another device. You cannot remove your last passkey.
				</p>
				{recoveryCodes && (
					<div className="space-y-2">
						<p>
							Save these recovery codes somewhere safe. Each code works once. Replacement
							invalidates previous codes.
						</p>
						<ul>
							{recoveryCodes.map((code) => (
								<li key={code}>
									<code className="break-all">{code}</code>
								</li>
							))}
						</ul>
					</div>
				)}
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
								setConfirmation({
									title: "Start passkey recovery?",
									description:
										"This removes all passkeys and ends your other sessions. Admin access stays blocked until you register and verify a new passkey.",
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
				confirmLabel="Confirm"
				onConfirm={(subject) => subject.action()}
				onClose={() => setConfirmation(null)}
			/>
		</section>
	);
}
