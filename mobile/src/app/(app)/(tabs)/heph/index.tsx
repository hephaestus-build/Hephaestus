import { Stack, useRouter } from "expo-router";

import { AccountButton } from "@/account/AccountButton";
import { useTabAccount } from "@/account/use-tab-account";
import { STARTERS } from "@/heph/drafts";
import { HephSays } from "@/heph/HephSays";
import { INTRODUCTION } from "@/heph/mentor-voice";
import { ACCESS_EXPLANATION, useHephAccess } from "@/heph/use-heph-access";
import { renewSession } from "@/session/session-store";
import { formatRelative } from "@/ui/dates";
import { QueryStates } from "@/ui/QueryStates";
import { Row } from "@/ui/Row";
import { Screen } from "@/ui/Screen";
import { Section } from "@/ui/Section";
import { StateView } from "@/ui/StateView";
import { WorkspaceLabel } from "@/workspace/WorkspaceLabel";

export default function HephThreads() {
	const account = useTabAccount();
	const router = useRouter();
	const { access, threads } = useHephAccess();
	const startNew = (starter?: number) =>
		router.push({
			pathname: "/conversation/[threadId]",
			params:
				starter === undefined ? { threadId: "new" } : { threadId: "new", starter: String(starter) },
		});

	// A grant takes effect with the next access token, so renew before asking again.
	const checkAgain = async () => {
		try {
			await renewSession();
		} catch {
			// Ended or unreachable; the refetch below says which.
		}
		await threads.refetch();
	};

	let body;
	if (access === "workspace-off") {
		body = (
			<StateView
				state="empty"
				icon={{ ios: "moon.zzz", android: "bedtime" }}
				{...ACCESS_EXPLANATION[access]}
			/>
		);
	} else if (access === "no-access") {
		body = (
			<StateView
				state="empty"
				icon={{ ios: "lock", android: "lock" }}
				{...ACCESS_EXPLANATION[access]}
				action={{
					title: "Check again",
					onPress: () => {
						void checkAgain();
					},
				}}
			/>
		);
	} else {
		body = (
			<QueryStates
				query={threads}
				loadingLabel="Loading your conversations"
				errorTitle="Could not load your conversations"
			>
				{(list) => (
					<>
						{/* Someone coming back looks for their conversations first, and the starters follow them. Before
						    the first one, Heph introduces itself instead. */}
						{list.length === 0 ? (
							<HephSays testID="heph-introduction">{INTRODUCTION}</HephSays>
						) : (
							<Section title="Conversations" appearance="plain">
								{/* A plain press, not a Link with a context menu: expo-router's native preview wrapper
								    hides the row from the accessibility tree. Deleting is in the conversation's menu. */}
								{list.map(({ id, title, createdAt }) =>
									id === undefined ? null : (
										<Row
											key={id}
											testID={`thread-${id}`}
											title={title ?? "Untitled conversation"}
											kind="static"
											subtitle={
												createdAt === undefined ? undefined : `Started ${formatRelative(createdAt)}`
											}
											onPress={() =>
												router.push({
													pathname: "/conversation/[threadId]",
													params: { threadId: id },
												})
											}
										/>
									),
								)}
							</Section>
						)}
						<Section
							title="Start with a question"
							footer="Each opens a new conversation with the question as a draft you can change."
							appearance="plain"
						>
							{STARTERS.map((starter, index) => (
								<Row
									key={starter}
									testID={`heph-starter-${index}`}
									title={starter}
									kind="static"
									onPress={() => startNew(index)}
								/>
							))}
						</Section>
					</>
				)}
			</QueryStates>
		);
	}

	return (
		<>
			<Stack.Screen options={{ title: "Heph" }} />
			<AccountButton
				avatarUrl={account.avatarUrl}
				loading={account.loading}
				onPress={() => router.push("/account")}
				{...(access === "available"
					? {
							action: {
								symbol: "square.and.pencil",
								label: "New conversation",
								onPress: () => startNew(),
								testID: "heph-new-conversation",
							},
						}
					: {})}
			/>
			{/* A conversation list reads best as a calm plain list on the page, not as settings cards. */}
			<Screen
				surface="plain"
				testID="heph-screen"
				onRefresh={() => {
					void threads.refetch();
				}}
				refreshing={threads.isRefetching}
			>
				{account.workspaceName === undefined ? null : (
					<WorkspaceLabel name={account.workspaceName} />
				)}
				{body}
			</Screen>
		</>
	);
}
