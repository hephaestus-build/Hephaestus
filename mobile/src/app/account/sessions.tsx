import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { Alert } from "react-native";

import { sessionLabel } from "@/account/session-label";
import {
	listSessionsOptions,
	listSessionsQueryKey,
	revokeOtherSessionsMutation,
	revokeSessionMutation,
} from "@/api/@tanstack/react-query.gen";
import type { SessionView } from "@/api/types.gen";
import { formatDate, formatRelative } from "@/ui/dates";
import { InlineError } from "@/ui/InlineError";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";

function describe(session: SessionView): string {
	return [
		session.current === true ? "This device" : null,
		session.issuedAt === undefined ? null : `active ${formatRelative(session.issuedAt)}`,
		session.expiresAt === undefined ? null : `until ${formatDate(session.expiresAt)}`,
	]
		.filter((part) => part !== null)
		.join(" · ");
}

export default function Sessions() {
	const queryClient = useQueryClient();
	const sessions = useQuery(listSessionsOptions({}));
	const refresh = () => {
		void queryClient.invalidateQueries({ queryKey: listSessionsQueryKey({}) });
	};
	const revoke = useMutation({ ...revokeSessionMutation(), onSettled: refresh });
	const revokeOthers = useMutation({ ...revokeOtherSessionsMutation(), onSettled: refresh });

	const confirmRevoke = (session: SessionView) => {
		const { jti } = session;
		if (jti === undefined) {
			return;
		}
		Alert.alert("Sign this device out?", sessionLabel(session.userAgent, session.nativeApp), [
			{ text: "Cancel", style: "cancel" },
			{ text: "Sign out", style: "destructive", onPress: () => revoke.mutate({ path: { jti } }) },
		]);
	};

	return (
		<>
			<Stack.Screen options={{ title: "Signed-in devices", headerLargeTitleEnabled: false }} />
			<Screen
				testID="sessions-screen"
				onRefresh={() => {
					void sessions.refetch();
				}}
				refreshing={sessions.isRefetching}
			>
				<QueryStates
					query={sessions}
					loadingLabel="Loading your devices"
					errorTitle="Could not load your devices"
				>
					{(list) => (
						<>
							<Section
								title="Signed in"
								footer="Signing a device out ends its session at once. The app on it asks to sign in again."
							>
								{list.map((session) => (
									<Row
										key={session.jti ?? describe(session)}
										title={sessionLabel(session.userAgent, session.nativeApp)}
										subtitle={describe(session)}
										kind={session.current === true ? "static" : "destructive"}
										onPress={session.current === true ? undefined : () => confirmRevoke(session)}
									/>
								))}
							</Section>
							{revoke.isError ? (
								<InlineError
									testID="revoke-error"
									message="That device is still signed in: signing it out did not go through."
									retrying={revoke.isPending}
									onRetry={() => revoke.mutate(revoke.variables)}
								/>
							) : null}
							{list.some((session) => session.current !== true) ? (
								<Section>
									<Row
										title="Sign out all other devices"
										kind="destructive"
										disabled={revokeOthers.isPending}
										onPress={() =>
											Alert.alert(
												"Sign out everywhere else?",
												"Every other browser and phone signed in to this account is signed out.",
												[
													{ text: "Cancel", style: "cancel" },
													{
														text: "Sign out others",
														style: "destructive",
														onPress: () => revokeOthers.mutate({}),
													},
												],
											)
										}
									/>
								</Section>
							) : null}
							{revokeOthers.isError ? (
								<InlineError
									testID="revoke-others-error"
									message="The other devices are still signed in: signing them out did not go through."
									retrying={revokeOthers.isPending}
									onRetry={() => revokeOthers.mutate({})}
								/>
							) : null}
						</>
					)}
				</QueryStates>
			</Screen>
		</>
	);
}
