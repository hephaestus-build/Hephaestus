import { Stack, useRouter } from "expo-router";
import { useState } from "react";
import { StyleSheet, TextInput, View } from "react-native";

import { IS_DEVELOPMENT_BUILD } from "@/build-variant";
import { instanceProblem, resolveInstance, setPendingInstance } from "@/instance/instance";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { Screen } from "@/ui/Screen";
import { radius, space, usePalette } from "@/ui/theme";

/** Another Hephaestus than the one the welcome screen offers: a team's own, or any other server. */
export default function OtherInstance() {
	const router = useRouter();
	const palette = usePalette();
	const [address, setAddress] = useState("");
	const [problem, setProblem] = useState<string | null>(null);
	const [pending, setPending] = useState(false);

	const submit = async () => {
		setPending(true);
		setProblem(null);
		const resolution = await resolveInstance(address, IS_DEVELOPMENT_BUILD);
		setPending(false);
		if (!resolution.ok) {
			setProblem(instanceProblem(resolution.reason));
			return;
		}
		setPendingInstance(resolution.instance);
		router.push("/sign-in");
	};

	return (
		<>
			<Stack.Screen options={{ title: "Another Hephaestus" }} />
			<Screen testID="instance-screen">
				<View style={styles.form}>
					<AppText variant="subheadline" tone="secondaryLabel" nativeID="address-label">
						The address your team opens Hephaestus at in a browser. Ask whoever set it up if you do
						not know it.
					</AppText>
					<TextInput
						testID="instance-address"
						value={address}
						onChangeText={setAddress}
						onSubmitEditing={() => {
							void submit();
						}}
						placeholder="hephaestus.example.org"
						placeholderTextColor={palette.tertiaryLabel}
						autoCapitalize="none"
						autoCorrect={false}
						keyboardType="url"
						textContentType="URL"
						returnKeyType="go"
						accessibilityLabelledBy="address-label"
						accessibilityLabel="Your team's Hephaestus address"
						style={[styles.input, { color: palette.label, backgroundColor: palette.card }]}
					/>
					{problem === null ? null : (
						<AppText
							variant="footnote"
							tone="danger"
							accessibilityLiveRegion="assertive"
							testID="instance-problem"
						>
							{problem}
						</AppText>
					)}
					<Button
						title="Continue"
						onPress={() => {
							void submit();
						}}
						pending={pending}
						testID="instance-continue"
					/>
				</View>
			</Screen>
		</>
	);
}

const styles = StyleSheet.create({
	form: { gap: space.md },
	input: {
		minHeight: 50,
		borderRadius: radius.control,
		borderCurve: "continuous",
		paddingHorizontal: space.lg,
		fontSize: 17,
	},
});
