import { StateView } from "@/ui/StateView";

/** What every practice page says in a workspace that does not review work against practices. */
export function PracticesOff({ workspaceName }: { workspaceName: string }) {
	return (
		<StateView
			state="empty"
			icon={{ ios: "moon.zzz", android: "bedtime" }}
			title="Practice reviews are off here"
			message={`${workspaceName} does not review work against practices, so there are no practices or feedback to show. Heph is one tab away.`}
		/>
	);
}
