import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { useEffect, useState } from "react";
import { Pressable, StyleSheet, Switch, View } from "react-native";

import {
	completeFirstLoginConsentMutation,
	getConsentStatusOptions,
	getConsentStatusQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { ANSWERS, RESEARCH, TERMS, TERMS_ACCEPTANCE, WORDING_VERSION } from "@/consent/wording";
import { webUrl } from "@/instance/instance";
import { markConsent, signOut, useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { Screen } from "@/ui/Screen";
import { Section, SectionItem } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { radius, space, usePalette } from "@/ui/theme";

/**
 * The first-login transparency step, and again whenever the terms change. Everything else waits for
 * it: the server answers 428 until it is done.
 */
export default function Consent() {
	const session = useSession();
	const router = useRouter();
	const queryClient = useQueryClient();
	const palette = usePalette();
	const notice = useQuery(getConsentStatusOptions({}));
	const [accepted, setAccepted] = useState(false);
	const [research, setResearch] = useState<boolean | null>(null);
	const complete = useMutation({
		...completeFirstLoginConsentMutation(),
		onSuccess: (status) => {
			queryClient.setQueryData(getConsentStatusQueryKey({}), status);
			if (status.completed) {
				markConsent("complete");
			}
		},
		onError: () => {
			void queryClient.invalidateQueries({ queryKey: getConsentStatusQueryKey({}) });
		},
	});
	const instance = session.status === "signedIn" ? session.instance : undefined;
	const completed = notice.data?.completed === true;

	useEffect(() => {
		if (completed) {
			markConsent("complete");
		}
	}, [completed]);

	if (notice.isPending) {
		return (
			<Screen>
				<StateView state="loading" label="Fetching your setup" />
			</Screen>
		);
	}
	if (notice.isError) {
		return (
			<Screen>
				<StateView
					state="error"
					title="Could not fetch your setup"
					message="Check your connection and try again."
					onRetry={() => {
						void notice.refetch();
					}}
				/>
				<Button
					title="Sign out"
					variant="secondary"
					onPress={() => {
						void signOut();
					}}
				/>
			</Screen>
		);
	}
	if (completed) {
		return null;
	}
	if (notice.data.noticeVersion !== WORDING_VERSION) {
		// These words are not the ones this server asks to accept; showing them would be a lie.
		return (
			<Screen>
				<StateView
					state="empty"
					icon={{ ios: "arrow.down.app", android: "system_update" }}
					title="The terms have changed"
					message="This version of the app shows older terms. Update the app, or read and accept the new terms in your browser."
					action={
						instance === undefined
							? undefined
							: {
									title: "Open in browser",
									onPress: () => {
										void WebBrowser.openBrowserAsync(`${webUrl(instance)}/consent`);
									},
								}
					}
				/>
				<Button
					title="Sign out"
					variant="secondary"
					onPress={() => {
						void signOut();
					}}
				/>
			</Screen>
		);
	}
	const organization = notice.data.researchOrganization;
	const asksResearch = organization !== undefined && organization !== "";
	const ready = accepted && (!asksResearch || research !== null);

	return (
		<Screen testID="consent-screen">
			<View style={styles.header}>
				<AppText variant="largeTitle" accessibilityRole="header">
					Before you start
				</AppText>
				<AppText variant="callout" tone="secondaryLabel">
					{asksResearch
						? "Two things first: the rules, and whether you'd like to take part in the research."
						: "One thing first: the rules."}
				</AppText>
			</View>
			<Section title="Terms and privacy">
				{TERMS.map((fact) => (
					<Fact key={fact.term} term={fact.term} detail={fact.detail} />
				))}
			</Section>
			{instance === undefined ? null : (
				<Button
					title="Read the privacy notice"
					variant="secondary"
					onPress={() => {
						void WebBrowser.openBrowserAsync(`${webUrl(instance)}/privacy`);
					}}
				/>
			)}
			<View style={[styles.toggle, { backgroundColor: palette.card }]}>
				<AppText style={styles.toggleLabel} nativeID="terms-label">
					{TERMS_ACCEPTANCE}
				</AppText>
				<Switch
					testID="consent-accept"
					value={accepted}
					onValueChange={setAccepted}
					accessibilityLabelledBy="terms-label"
					accessibilityLabel={TERMS_ACCEPTANCE}
				/>
			</View>
			{asksResearch ? (
				<>
					<Section
						title="Take part in the research?"
						footer={`Optional, and Hephaestus works the same either way. The research is run by ${organization}. You can change your answer later.`}
					>
						{RESEARCH.map((fact) => (
							<Fact key={fact.term} term={fact.term} detail={fact.detail} />
						))}
					</Section>
					<View style={styles.answers} accessibilityRole="radiogroup">
						{ANSWERS.map((answer) => {
							const selected = research === answer.value;
							return (
								<Pressable
									key={answer.title}
									testID={`consent-research-${answer.value ? "yes" : "no"}`}
									onPress={() => setResearch(answer.value)}
									accessibilityRole="radio"
									accessibilityState={{ checked: selected }}
									style={[
										styles.answer,
										{
											backgroundColor: palette.card,
											borderColor: selected ? palette.accent : palette.separator,
										},
									]}
								>
									<AppText variant="headline">{answer.title}</AppText>
									<AppText variant="subheadline" tone="secondaryLabel">
										{answer.detail}
									</AppText>
								</Pressable>
							);
						})}
					</View>
				</>
			) : null}
			{complete.isError ? (
				<AppText tone="danger" accessibilityLiveRegion="assertive">
					Your answers weren&apos;t saved. Please try again.
				</AppText>
			) : null}
			<Button
				testID="consent-continue"
				title="Continue"
				disabled={!ready}
				pending={complete.isPending}
				onPress={() =>
					complete.mutate({
						body: {
							noticeVersion: notice.data.noticeVersion,
							termsAccepted: accepted,
							...(asksResearch && research !== null
								? { participateInResearch: research, researchOrganization: organization }
								: {}),
						},
					})
				}
			/>
			<Button
				title="Sign out"
				variant="secondary"
				onPress={() => {
					void signOut();
				}}
			/>
			<Button
				testID="consent-privacy"
				title="Privacy and account"
				variant="plain"
				accessibilityHint="Read the privacy notice or delete your account without accepting"
				onPress={() => router.push("/account/privacy")}
			/>
		</Screen>
	);
}

function Fact({ term, detail }: { term: string; detail: string }) {
	return (
		<SectionItem>
			<View style={styles.fact}>
				<AppText variant="headline">{term}</AppText>
				<AppText variant="subheadline" tone="secondaryLabel">
					{detail}
				</AppText>
			</View>
		</SectionItem>
	);
}

const styles = StyleSheet.create({
	header: { gap: space.sm },
	fact: { padding: space.lg, gap: space.xs },
	toggle: {
		flexDirection: "row",
		alignItems: "center",
		gap: space.md,
		padding: space.lg,
		borderRadius: radius.card,
		borderCurve: "continuous",
	},
	toggleLabel: { flex: 1 },
	answers: { gap: space.md },
	answer: {
		padding: space.lg,
		gap: space.xs,
		borderRadius: radius.card,
		borderCurve: "continuous",
		borderWidth: 2,
	},
});
