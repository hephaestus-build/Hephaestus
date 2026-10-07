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
import { assertPasskey, createPasskey, passkeysSupported } from "@/lib/passkeys";
import { type StepUpChallenge, stepUpChallengeOf, problemDetailOf } from "@/lib/problem-detail";
import { withSessionLock } from "@/runtime/auth/session-lock";

import type { PasskeyAction, PasskeySectionProps } from "@/components/settings/PasskeySection";

export function usePasskeySettings() {
	const queryClient = useQueryClient();
	const [challenge, setChallenge] = useState<StepUpChallenge>();
	const confirmation = useConfirmAccess(challenge !== undefined);
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
		try {
			const { data } = await getPasskeyRegistrationOptions({ throwOnError: true });
			const credentialJson = await createPasskey(data.optionsJson);
			await mutation.mutateAsync({
				kind: "register",
				execute: async () => {
					await withSessionLock(async () =>
						registerPasskey({
							body: { challengeId: data.challengeId, credentialJson, label },
							throwOnError: true,
						}),
					);
				},
			});
		} catch (error) {
			setCeremonyError(error);
			setChallenge(stepUpChallengeOf(error));
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
		}
		setCeremony(undefined);
	};
	let error: string | undefined;
	if (ceremonyError !== undefined) {
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
