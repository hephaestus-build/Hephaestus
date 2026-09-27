import { Redirect } from "expo-router";

import { useSession } from "@/session/session-store";

/** Home is decided once the session is known; until then the splash screen covers this. */
export default function Index() {
	const session = useSession();
	if (session.status === "restoring") {
		return null;
	}
	return <Redirect href={session.status === "signedIn" ? "/practice" : "/welcome"} />;
}
