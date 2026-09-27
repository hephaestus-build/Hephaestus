import * as Application from "expo-application";
import { Stack } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import { Image, StyleSheet, View } from "react-native";

import appIcon from "@assets/icon.png";

import { webUrl } from "@/instance/instance";
import { useSession } from "@/session/session-store";
import { AppText } from "@/ui/AppText";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { space } from "@/ui/theme";

const PROJECT = "https://github.com/hephaestus-build/Hephaestus";
const GUIDE = "https://docs.hephaestus.build/user/mobile-app";

function open(url: string): void {
	void WebBrowser.openBrowserAsync(url);
}

/** What this app is, which Hephaestus it talks to, and where to read, report and look further. */
export default function About() {
	const session = useSession();
	const instance = session.status === "signedIn" ? session.instance : undefined;
	return (
		<>
			<Stack.Screen options={{ title: "About", headerLargeTitleEnabled: false }} />
			<Screen testID="about-screen">
				<View style={styles.identity} accessible accessibilityRole="header">
					<Image source={appIcon} style={styles.icon} accessibilityIgnoresInvertColors />
					<View style={styles.text}>
						<AppText variant="title">Hephaestus</AppText>
						<AppText variant="subheadline" tone="secondaryLabel" selectable>
							{`Version ${Application.nativeApplicationVersion ?? "unknown"} (${Application.nativeBuildVersion ?? "local"})`}
						</AppText>
					</View>
				</View>
				{instance === undefined ? null : (
					<Section
						title="Your Hephaestus"
						footer="Your team runs this Hephaestus. Its privacy notice says who, what it keeps and for how long."
					>
						<Row
							title={instance.label}
							icon={{ ios: "server.rack", android: "dns" }}
							kind="static"
						/>
						<Row
							title="Privacy notice"
							icon={{ ios: "hand.raised", android: "privacy_tip" }}
							onPress={() => open(`${webUrl(instance)}/privacy`)}
						/>
					</Section>
				)}
				<Section footer="Hephaestus is open source. Reports go to its public issue tracker, so leave out anything private.">
					<Row
						title="Guide to the app"
						icon={{ ios: "book", android: "menu_book" }}
						onPress={() => open(GUIDE)}
					/>
					<Row
						title="Report a problem"
						icon={{ ios: "exclamationmark.bubble", android: "feedback" }}
						onPress={() => open(`${PROJECT}/issues`)}
					/>
					<Row
						title="Source code"
						icon={{ ios: "chevron.left.forwardslash.chevron.right", android: "code" }}
						onPress={() => open(PROJECT)}
					/>
				</Section>
			</Screen>
		</>
	);
}

const styles = StyleSheet.create({
	identity: { flexDirection: "row", alignItems: "center", gap: space.lg },
	icon: { width: 64, height: 64, borderRadius: 14, borderCurve: "continuous" },
	text: { flex: 1, gap: space.xs },
});
