import { Stack, useRouter } from "expo-router";
import { Pressable, StyleSheet, View } from "react-native";

import { AccountButton } from "@/account/AccountButton";
import { useTabAccount } from "@/account/use-tab-account";
import { groupVisual } from "@/practice/group-visual";
import { GroupCard } from "@/practice/GroupCard";
import { PracticesOff } from "@/practice/PracticesOff";
import {
	coverageNote,
	type GroupEntry,
	isObserved,
	leadingNextStep,
	tally,
} from "@/practice/profile";
import { StandingLabel } from "@/practice/Standing";
import { usePracticeProfile } from "@/practice/use-practice-profile";
import { STANDING } from "@/practice/vocabulary";
import { AppText } from "@/ui/AppText";
import { Icon } from "@/ui/Icon";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { radius, space, usePalette } from "@/ui/theme";
import { useWorkspace } from "@/workspace/workspace-context";
import { WorkspaceLabel } from "@/workspace/WorkspaceLabel";

/**
 * The developer's practice profile: where they stand in each practice group their workspace reviews,
 * from their own reviewed work. It leads with the groups that have evidence, most in need of attention
 * first, and the next step the server suggests for the first of them; groups without evidence yet
 * follow as a short list. Practice feedback has its own page, because reading it delivers it: nothing
 * here reads it or counts it.
 */
export default function Practice() {
	const account = useTabAccount();
	const router = useRouter();
	const workspace = useWorkspace();
	const profile = usePracticeProfile();

	let body;
	if (workspace.practicesEnabled) {
		body = (
			<QueryStates
				query={profile}
				loadingLabel="Loading your practices"
				errorTitle="Could not load your practices"
				empty={{
					when: (groups) => groups.length === 0,
					view: (
						<>
							<StateView
								state="empty"
								icon={{ ios: "list.bullet.clipboard", android: "checklist" }}
								title="No practice groups here yet"
								message={`${workspace.displayName} has not chosen which practices to review yet.`}
							/>
							<FeedbackDoorway />
						</>
					),
				}}
			>
				{(groups) => <Profile groups={groups} />}
			</QueryStates>
		);
	} else {
		body = <PracticesOff workspaceName={workspace.displayName} />;
	}

	return (
		<>
			<Stack.Screen options={{ title: "Practice" }} />
			<AccountButton
				avatarUrl={account.avatarUrl}
				loading={account.loading}
				onPress={() => router.push("/account")}
			/>
			<Screen
				testID="practice-screen"
				onRefresh={profile.refetch}
				refreshing={profile.isRefetching}
			>
				{account.workspaceName === undefined ? null : (
					<WorkspaceLabel name={account.workspaceName} />
				)}
				{body}
			</Screen>
		</>
	);
}

function Profile({ groups }: { groups: GroupEntry[] }) {
	const router = useRouter();
	const workspace = useWorkspace();
	const observed = groups.filter(isObserved);
	const unobserved = groups.filter((entry) => !isObserved(entry));
	const next = leadingNextStep(groups);
	const openGroup = (groupSlug: string) =>
		router.push({ pathname: "/practice/group/[groupSlug]", params: { groupSlug } });

	return (
		<>
			<Overview groups={groups} />
			{next === undefined ? null : (
				<NextStep
					groupName={next.entry.group.name}
					guidance={next.guidance}
					onPress={() => openGroup(next.entry.group.slug)}
				/>
			)}
			{observed.length === 0 ? null : (
				<View style={styles.cards}>
					<AppText variant="title" accessibilityRole="header">
						Practice groups
					</AppText>
					{observed.map((entry) => (
						<GroupCard
							key={entry.group.slug}
							entry={entry}
							provider={workspace.providerType}
							onPress={() => openGroup(entry.group.slug)}
						/>
					))}
				</View>
			)}
			<FeedbackDoorway />
			{unobserved.length === 0 ? null : (
				<Section
					title="Not observed yet"
					footer={
						observed.length === 0
							? "Hephaestus reviews your work against these practices. Where you stand appears once reviewed work touches them."
							: "No reviewed work has touched these practices yet. Open one to see what it looks for."
					}
				>
					{unobserved.map((entry) => (
						<Row
							key={entry.group.slug}
							testID={`group-${entry.group.slug}`}
							title={entry.group.name}
							icon={groupVisual(entry.group.icon, entry.group.color).icon}
							subtitle={
								entry.practices.length === 1 ? "1 practice" : `${entry.practices.length} practices`
							}
							onPress={() => openGroup(entry.group.slug)}
						/>
					))}
				</Section>
			)}
		</>
	);
}

/**
 * Where the developer stands, at a glance: how many groups stand where, counted only among groups with
 * observations. Groups not observed yet are coverage, not a result, so they get one quiet line rather
 * than a number of their own. Before any observation, it says what will appear and where to look.
 */
function Overview({ groups }: { groups: GroupEntry[] }) {
	const palette = usePalette();
	const parts = tally(groups.filter(isObserved));
	const coverage = coverageNote(groups);
	const intro =
		parts.length === 0
			? "This takes shape as your work is reviewed. Until then, open a practice group below to see what it looks for."
			: "From recent reviews of your own work.";
	return (
		<View
			testID="practice-overview"
			style={[styles.overview, { backgroundColor: palette.card }]}
			accessible
			accessibilityLabel={[
				"Where you stand",
				intro,
				...parts.map(({ standing, count }) => `${count} ${STANDING[standing].shortLabel}`),
				coverage,
			]
				.filter((part) => part !== undefined)
				.join(", ")}
		>
			<View style={styles.intro}>
				<AppText variant="headline">Where you stand</AppText>
				<AppText variant="subheadline" tone="secondaryLabel">
					{intro}
				</AppText>
			</View>
			{parts.length === 0 ? null : (
				<View style={styles.tally}>
					{parts.map(({ standing, count }) => (
						<View key={standing} style={styles.tallyItem}>
							<AppText variant="title">{count}</AppText>
							<StandingLabel standing={standing} length="short" variant="footnote" />
						</View>
					))}
				</View>
			)}
			{coverage === undefined ? null : (
				<AppText variant="footnote" tone="secondaryLabel">
					{coverage}
				</AppText>
			)}
		</View>
	);
}

/** The server's suggested next step for the group most in need of attention, quoted as it wrote it. */
function NextStep({
	groupName,
	guidance,
	onPress,
}: {
	groupName: string;
	guidance: string;
	onPress: () => void;
}) {
	const palette = usePalette();
	return (
		<Pressable
			testID="next-step"
			onPress={onPress}
			accessibilityRole="button"
			accessibilityLabel={`Suggested next step, from your reviews in ${groupName}: ${guidance}`}
			accessibilityHint="Opens the group"
			style={({ pressed }) => [
				styles.next,
				{ backgroundColor: pressed ? palette.fill : palette.card },
			]}
		>
			<View style={styles.nextLabel}>
				<Icon
					name={{ ios: "arrow.forward.circle.fill", android: "arrow_circle_right" }}
					size={17}
					tintColor={palette.accent}
				/>
				<AppText variant="footnote" weight="semibold" tone="accent" style={styles.shrink}>
					{`Suggested next step · ${groupName}`}
				</AppText>
			</View>
			<AppText variant="body">{guidance}</AppText>
		</Pressable>
	);
}

/**
 * The way to practice feedback. Deliberately bare: no count, no preview, because only reading the
 * feedback may say how much there is, and reading it is what delivers it.
 */
function FeedbackDoorway() {
	const router = useRouter();
	return (
		<Section>
			<Row
				testID="practice-feedback"
				title="Practice feedback"
				subtitle="What reviews of your work suggest you try next"
				icon={{ ios: "text.bubble", android: "chat" }}
				onPress={() => router.push("/practice/feedback")}
			/>
		</Section>
	);
}

const styles = StyleSheet.create({
	overview: {
		padding: space.lg,
		gap: space.md,
		borderRadius: radius.card,
		borderCurve: "continuous",
	},
	tally: { flexDirection: "row", flexWrap: "wrap", columnGap: space.xl, rowGap: space.md },
	tallyItem: { gap: space.xs },
	intro: { gap: space.xs },
	next: { padding: space.lg, gap: space.sm, borderRadius: radius.card, borderCurve: "continuous" },
	nextLabel: { flexDirection: "row", alignItems: "center", gap: space.sm },
	shrink: { flexShrink: 1 },
	cards: { gap: space.md },
});
