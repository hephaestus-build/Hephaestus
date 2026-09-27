import { useRouter } from "expo-router";
import { useState } from "react";
import { Image, ScrollView, StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import appIcon from "@assets/icon.png";

import { DEFAULT_INSTANCE, IS_DEVELOPMENT_BUILD } from "@/build-variant";
import { addressLabel } from "@/instance/default-instance";
import { instanceProblem, resolveInstance, setPendingInstance } from "@/instance/instance";
import { useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Button } from "@/ui/Button";
import { COLUMN, useColumnPadding } from "@/ui/layout";
import { radius, space, usePalette } from "@/ui/theme";

const NOTICES = {
	ended: "Your session ended. Sign in again to continue.",
	reinstalled: "Hephaestus was reinstalled on this device. Sign in again to continue.",
} as const;

/**
 * The front door: one prominent way in, to the Hephaestus this build is for — hephaestus.build in the
 * store app, this worktree's server in a development build. Any other is one deliberate step away.
 */
export default function Welcome() {
	const router = useRouter();
	const palette = usePalette();
	const insets = useSafeAreaInsets();
	const column = useColumnPadding(space.xl, COLUMN.actions);
	const session = useSession();
	const defaultAddress = DEFAULT_INSTANCE;
	const [problem, setProblem] = useState<string | null>(null);
	const [pending, setPending] = useState(false);

	const notice =
		session.status === "signedOut" && session.notice !== undefined ? NOTICES[session.notice] : null;

	const signInTo = async (address: string) => {
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
		<ScrollView
			style={{ backgroundColor: palette.background }}
			contentContainerStyle={[
				styles.content,
				{ paddingTop: insets.top + space.xl, paddingBottom: insets.bottom + space.lg },
				column,
			]}
		>
			<View style={styles.brand}>
				<Image source={appIcon} style={styles.mark} accessibilityIgnoresInvertColors />
				{/* The name is a wordmark: kept whole on one line, shrinking at the largest text sizes. */}
				<AppText
					variant="largeTitle"
					accessibilityRole="header"
					numberOfLines={1}
					adjustsFontSizeToFit
					minimumFontScale={0.6}
					style={styles.wordmark}
				>
					Hephaestus
				</AppText>
				<AppText variant="body" tone="secondaryLabel" style={styles.centered}>
					See where you stand on the practices your team cares about, from reviews of the work you
					already do, and talk it through with Heph, your AI mentor.
				</AppText>
			</View>
			<View style={styles.actions}>
				{notice === null ? null : (
					<View
						style={[styles.notice, { backgroundColor: palette.fill }]}
						accessibilityLiveRegion="polite"
					>
						<AppText variant="subheadline">{notice}</AppText>
					</View>
				)}
				{defaultAddress === undefined ? null : (
					<>
						<Button
							testID="instance-default"
							title="Continue"
							pending={pending}
							accessibilityHint={`Signs in to ${addressLabel(defaultAddress)}, or creates your account there the first time`}
							onPress={() => {
								void signInTo(defaultAddress);
							}}
						/>
						<AppText variant="footnote" tone="secondaryLabel" style={styles.centered}>
							{IS_DEVELOPMENT_BUILD
								? `On ${addressLabel(defaultAddress)}, the development server this build was made for`
								: `On ${addressLabel(defaultAddress)}. New here? Continuing creates your account.`}
						</AppText>
					</>
				)}
				{problem === null ? null : (
					<AppText
						variant="footnote"
						tone="danger"
						accessibilityLiveRegion="assertive"
						testID="default-problem"
						style={styles.centered}
					>
						{problem}
					</AppText>
				)}
				{/* Held while the default address is being checked, so that check cannot finish into a
				    sign-in after the person has chosen another Hephaestus. */}
				<Button
					testID="instance-other"
					title="Use another Hephaestus"
					variant={defaultAddress === undefined ? "primary" : "plain"}
					disabled={pending}
					accessibilityHint="For a team that runs Hephaestus at its own address"
					onPress={() => router.push("/instance")}
				/>
			</View>
		</ScrollView>
	);
}

const styles = StyleSheet.create({
	content: {
		flexGrow: 1,
		justifyContent: "space-between",
		gap: space.xl * 2,
	},
	brand: { flexGrow: 1, alignItems: "center", justifyContent: "center", gap: space.md },
	mark: { width: 96, height: 96, borderRadius: 22, borderCurve: "continuous" },
	centered: { textAlign: "center" },
	wordmark: { textAlign: "center", alignSelf: "stretch" },
	notice: { padding: space.md, borderRadius: radius.control, borderCurve: "continuous" },
	actions: { gap: space.sm },
});
