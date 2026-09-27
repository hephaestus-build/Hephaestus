/**
 * Push work outlives the render that started it: a permission prompt, a push token or a workspace
 * switch can resolve after the person signed out or into another account. Each step here runs only
 * while the session that started it is still the current one, so a late answer never registers,
 * unregisters or navigates on behalf of a different session.
 */

export type Ownership = "done" | "skipped" | "superseded" | "not-ours";

export interface RegistrationSteps {
	/** Whether the session that started this is still the current one. */
	stillCurrent: () => boolean;
	/** Undefined when this build cannot receive push at all. */
	projectId: string | undefined;
	permitted: () => Promise<boolean>;
	pushToken: (projectId: string) => Promise<string>;
	send: (pushToken: string) => Promise<void>;
}

export async function registerForSession(steps: RegistrationSteps): Promise<Ownership> {
	const { projectId } = steps;
	if (projectId === undefined || !steps.stillCurrent()) {
		return projectId === undefined ? "skipped" : "superseded";
	}
	if (!(await steps.permitted())) {
		return "skipped";
	}
	if (!steps.stillCurrent()) {
		return "superseded";
	}
	const token = await steps.pushToken(projectId);
	if (!steps.stillCurrent()) {
		return "superseded";
	}
	await steps.send(token);
	return "done";
}

export interface OpeningSteps {
	/** The sign-in the notification was sent to. */
	addressedTo: string;
	/** The sign-in on this device now, if any; it survives relaunches and refreshes. */
	currentSignIn: () => string | undefined;
	stillCurrent: () => boolean;
	selectWorkspace: () => Promise<void>;
	showFeedback: () => void;
}

/**
 * A notification tap: its workspace, then practice feedback — only for the sign-in it was sent to, and unless
 * another session took over in between. A tap on a notification sent to an earlier sign-in, on this
 * or another account or instance, opens the app and nothing more.
 */
export async function openForSession(steps: OpeningSteps): Promise<Ownership> {
	if (steps.currentSignIn() !== steps.addressedTo) {
		return "not-ours";
	}
	if (!steps.stillCurrent()) {
		return "superseded";
	}
	await steps.selectWorkspace();
	if (!steps.stillCurrent()) {
		return "superseded";
	}
	steps.showFeedback();
	return "done";
}
