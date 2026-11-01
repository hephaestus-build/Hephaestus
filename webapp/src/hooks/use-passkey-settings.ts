import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { getPasskeyStatusOptions } from "@/api/@tanstack/react-query.gen";
import {
	createPasskeyRecoveryCodes,
	getPasskeyRegistrationOptions,
	getPasskeyVerificationOptions,
	recoverPasskeys,
	registerPasskey,
	removePasskey,
	updatePasskeyProtection,
	verifyPasskey,
} from "@/api/sdk.gen";
import type { ConfirmAccessDialogProps } from "@/components/auth/ConfirmAccessDialog";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import {
	assertPasskey,
	createPasskey,
	defaultPasskeyLabel,
	passkeyPromptCanceled,
	passkeysSupported,
} from "@/lib/passkeys";
import {
	isProblemType,
	type StepUpChallenge,
	stepUpChallengeOf,
	problemDetailOf,
} from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { withSessionLock } from "@/runtime/auth/session-lock";

import type { PasskeyAction, PasskeySectionProps } from "@/components/settings/PasskeySection";

// The browser reports a closed, timed-out or declined prompt alike, so the copy blames none of them.
const PROMPT_CANCELED = "The passkey check did not finish. Try again when you’re ready.";

/**
 * The server's `passkey-required` refusal sends the reader to passkey settings, where this refusal
 * is read, so this page says what to do here instead.
 */
const CONFIRMATION_EXPIRED =
	"Your passkey confirmation has expired. Verify your passkey again, then retry.";

/**
 * The one owner of passkey enrollment, verification and management, for settings and for the
 * challenge page alike. `onVerified` runs only after the server accepted a verification the
 * developer started; nothing that the server refused before is sent again. `signInReturnTo` is
 * where a recent sign-in, asked for before a first passkey, comes back to; by default this page.
 */
export function usePasskeySettings({
	onVerified,
	signInReturnTo,
}: { onVerified?: () => void; signInReturnTo?: string } = {}) {
	const queryClient = useQueryClient();
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmation = useConfirmAccess(challenge !== undefined, signInReturnTo);
	const status = useQuery(getPasskeyStatusOptions());
	const [recoveryCodes, setRecoveryCodes] = useState<string[]>();
	const [ceremony, setCeremony] = useState<PasskeyAction>();
	const [ceremonyError, setCeremonyError] = useState<unknown>();
	const mutation = useMutation({
		mutationFn: async (action: {
			kind: PasskeyAction;
			credentialId?: string;
			execute: () => Promise<void>;
		}) => action.execute(),
		onMutate: () => {
			setCeremonyError(undefined);
		},
		onError: (error) => {
			setChallenge(stepUpChallengeOf(error));
		},
		onSettled: async () => {
			await queryClient.invalidateQueries();
		},
	});
	const register = async (label: string) => {
		setCeremony("register");
		setCeremonyError(undefined);
		const name = hasText(label.trim()) ? label.trim() : defaultPasskeyLabel(new Date());
		try {
			const { data } = await getPasskeyRegistrationOptions({ throwOnError: true });
			const credentialJson = await createPasskey(data.optionsJson);
			await mutation.mutateAsync({
				kind: "register",
				execute: async () => {
					await withSessionLock(async () =>
						registerPasskey({
							body: { challengeId: data.challengeId, credentialJson, label: name },
							throwOnError: true,
						}),
					);
				},
			});
		} catch (error) {
			setCeremonyError(error);
			setChallenge(stepUpChallengeOf(error));
			// Refused before the mutation, which is what reads the status after every change: read it
			// here, or a confirmation that aged out still shows as recent and hides Verify.
			if (isProblemType(error, "passkey-required")) {
				void status.refetch();
			}
		}
		setCeremony(undefined);
	};
	const verify = async () => {
		setCeremony("verify");
		setCeremonyError(undefined);
		try {
			const { data } = await getPasskeyVerificationOptions({ throwOnError: true });
			const credentialJson = await assertPasskey(data.optionsJson);
			await mutation.mutateAsync({
				kind: "verify",
				execute: async () => {
					await withSessionLock(async () =>
						verifyPasskey({
							body: { challengeId: data.challengeId, credentialJson },
							throwOnError: true,
						}),
					);
				},
			});
		} catch (error) {
			setCeremonyError(error);
			setChallenge(stepUpChallengeOf(error));
			setCeremony(undefined);
			return;
		}
		setCeremony(undefined);
		onVerified?.();
	};
	// A new browser/options failure takes precedence over the previous mutation's refusal.
	let operationError = ceremonyError;
	if (operationError === undefined && mutation.isError) {
		operationError = mutation.error;
	}
	let error: string | undefined;
	if (passkeyPromptCanceled(operationError)) {
		error = PROMPT_CANCELED;
	} else if (isProblemType(operationError, "passkey-required")) {
		error = CONFIRMATION_EXPIRED;
	} else if (ceremonyError !== undefined) {
		error = problemDetailOf(ceremonyError, "The passkey operation did not complete. Try again.");
	} else if (mutation.isError) {
		error = problemDetailOf(
			mutation.error,
			"We could not complete the passkey operation. Try again.",
		);
	} else if (status.isError) {
		error = problemDetailOf(status.error);
	}
	return {
		/** The action the server last accepted in this visit, such as a registration just finished. */
		completed: mutation.isSuccess ? mutation.variables.kind : undefined,
		/** The failed read of the passkey status as the server answered it, for a caller that classifies it. */
		statusError: status.error,
		section: {
			status: status.data,
			loading: status.isPending,
			pending: ceremony !== undefined || mutation.isPending,
			pendingAction: ceremony ?? mutation.variables?.kind,
			pendingCredentialId: mutation.variables?.credentialId,
			supported: passkeysSupported(),
			recoveryCodes,
			error,
			onRetry: () => {
				void status.refetch();
			},
			onRegister: (label) => {
				void register(label);
			},
			onVerify: () => {
				void verify();
			},
			onProtection: (enabled) =>
				mutation.mutate({
					kind: "protection",
					execute: async () => {
						await withSessionLock(async () =>
							updatePasskeyProtection({ body: { enabled }, throwOnError: true }),
						);
					},
				}),
			onRemove: (credentialId) =>
				mutation.mutate({
					kind: "remove",
					credentialId,
					execute: async () => {
						await withSessionLock(async () =>
							removePasskey({ path: { credentialId }, throwOnError: true }),
						);
						setRecoveryCodes(undefined);
					},
				}),
			onCreateRecoveryCodes: () =>
				mutation.mutate({
					kind: "codes",
					execute: async () => {
						setRecoveryCodes(undefined);
						const { data } = await createPasskeyRecoveryCodes({ throwOnError: true });
						setRecoveryCodes(data.codes);
					},
				}),
			onRecover: (code) =>
				mutation.mutate({
					kind: "recover",
					execute: async () => {
						await withSessionLock(async () =>
							recoverPasskeys({ body: { code }, throwOnError: true }),
						);
						setRecoveryCodes(undefined);
					},
				}),
		} satisfies PasskeySectionProps,
		confirmation: {
			open: challenge !== undefined,
			onOpenChange: (open) => {
				if (!open) {
					setChallenge(undefined);
				}
			},
			maxAgeSeconds: challenge?.maxAgeSeconds,
			providers: confirmation.providers,
			loading: confirmation.loading,
			error: confirmation.error,
			onRetry: confirmation.retry,
			onSignIn: confirmation.signIn,
		} satisfies ConfirmAccessDialogProps,
	};
}
