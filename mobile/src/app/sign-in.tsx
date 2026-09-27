import { useQuery } from "@tanstack/react-query";
import { Redirect } from "expo-router";
import { useRef, useState } from "react";
import { Keyboard, StyleSheet, TextInput, View } from "react-native";

import { listIdentityProvidersQueryKey } from "@/api/@tanstack/react-query.gen";
import { listIdentityProviders } from "@/api/sdk.gen";
import type { IdentityProviderView } from "@/api/types.gen";
import { IS_DEVELOPMENT_BUILD } from "@/build-variant";
import { pendingInstance, publicClient } from "@/instance/instance";
import { signIn } from "@/session/sign-in";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { QueryStates } from "@/ui/QueryStates";
import { Screen } from "@/ui/Screen";
import { StateView } from "@/ui/StateView";
import { radius, space, usePalette } from "@/ui/theme";

/** Slack and Outline only attach to an account that already exists; they are not ways to sign in. */
const LINK_ONLY = new Set(["SLACK", "OUTLINE"]);

export default function SignIn() {
	const instance = pendingInstance();
	const palette = usePalette();
	const [pending, setPending] = useState<string | null>(null);
	const [problem, setProblem] = useState<string | null>(null);
	const [devUsername, setDevUsername] = useState("");
	// One sign-in at a time: a second press, or the return key while the browser is opening, starts
	// nothing. State alone cannot say so until the next render.
	const inFlight = useRef(false);

	const providers = useQuery({
		queryKey: listIdentityProvidersQueryKey({ baseUrl: instance?.apiBaseUrl }),
		enabled: instance !== undefined,
		queryFn: async () => {
			if (instance === undefined) {
				return [];
			}
			const { data } = await listIdentityProviders({
				client: publicClient(instance.apiBaseUrl),
				throwOnError: true,
			});
			return data;
		},
	});

	if (instance === undefined) {
		return <Redirect href="/welcome" />;
	}

	const start = async (key: string, method: { provider: string } | { devUsername: string }) => {
		if (inFlight.current) {
			return;
		}
		inFlight.current = true;
		// The browser sheet opens over this screen; the keyboard goes first rather than waiting behind it.
		Keyboard.dismiss();
		setPending(key);
		setProblem(null);
		// Resolves with an outcome whatever happens, so the guard is always released.
		const result = await signIn(instance, method);
		inFlight.current = false;
		setPending(null);
		if (result.kind === "failed") {
			setProblem(result.message);
		}
	};

	const options = (providers.data ?? []).filter(
		(provider): provider is IdentityProviderView & { registrationId: string } =>
			provider.registrationId !== undefined && !LINK_ONLY.has(provider.providerType ?? ""),
	);
	const federated = options.filter((provider) => provider.providerType !== "DEV");
	const devOffered =
		IS_DEVELOPMENT_BUILD && options.some((provider) => provider.providerType === "DEV");
	// The return key and the button submit the same way. On a small screen the keyboard can cover the
	// button, so the return key is the one a person reaches.
	const devName = devUsername.trim();
	const submitDev = () => {
		if (devName !== "") {
			void start("dev", { devUsername: devName });
		}
	};

	return (
		<Screen>
			<View style={styles.header}>
				<AppText variant="title" accessibilityRole="header">
					{instance.label}
				</AppText>
				<AppText variant="subheadline" tone="secondaryLabel">
					Continue with the account your team uses for its code. The first time, this creates your
					Hephaestus account; after that, it signs you in. Hephaestus never sees your password.
				</AppText>
				<AppText variant="subheadline" tone="secondaryLabel">
					Your team’s workspace appears once it uses Hephaestus and you are a member.
				</AppText>
			</View>
			<QueryStates
				query={providers}
				loadingLabel="Finding the ways to sign in"
				errorTitle="Could not reach this Hephaestus"
				empty={{
					when: () => federated.length === 0 && !devOffered,
					view: (
						<StateView
							state="empty"
							icon={{ ios: "person.crop.circle.badge.questionmark", android: "person_off" }}
							title="No way to sign in yet"
							message="This Hephaestus has no sign-in provider set up. Its administrator can add GitHub or GitLab."
						/>
					),
				}}
			>
				{() => (
					<View style={styles.options}>
						{federated.map((provider) => (
							<Button
								key={provider.registrationId}
								testID={`sign-in-${provider.registrationId}`}
								title={`Continue with ${provider.displayName ?? provider.registrationId}`}
								onPress={() => {
									void start(provider.registrationId, { provider: provider.registrationId });
								}}
								pending={pending === provider.registrationId}
								disabled={pending !== null && pending !== provider.registrationId}
							/>
						))}
						{devOffered ? (
							<View style={[styles.dev, { borderColor: palette.separator }]}>
								<AppText variant="footnote" tone="secondaryLabel">
									Development sign-in, offered by this local server only
								</AppText>
								<TextInput
									testID="dev-username"
									value={devUsername}
									onChangeText={setDevUsername}
									onSubmitEditing={submitDev}
									returnKeyType="go"
									enablesReturnKeyAutomatically
									editable={pending === null}
									placeholder="username"
									placeholderTextColor={palette.tertiaryLabel}
									autoCapitalize="none"
									autoCorrect={false}
									accessibilityLabel="Development username"
									style={[styles.input, { color: palette.label, backgroundColor: palette.fill }]}
								/>
								<Button
									testID="sign-in-dev"
									title="Sign in for development"
									variant="secondary"
									onPress={submitDev}
									pending={pending === "dev"}
									disabled={devName === "" || (pending !== null && pending !== "dev")}
								/>
							</View>
						) : null}
					</View>
				)}
			</QueryStates>
			{problem === null ? null : (
				<AppText
					variant="subheadline"
					tone="danger"
					accessibilityLiveRegion="assertive"
					testID="sign-in-problem"
				>
					{problem}
				</AppText>
			)}
		</Screen>
	);
}

const styles = StyleSheet.create({
	header: { gap: space.sm },
	options: { gap: space.md },
	dev: {
		gap: space.sm,
		borderTopWidth: StyleSheet.hairlineWidth,
		paddingTop: space.lg,
		marginTop: space.sm,
	},
	input: {
		minHeight: 46,
		borderRadius: radius.control,
		borderCurve: "continuous",
		paddingHorizontal: space.lg,
		fontSize: 17,
	},
});
