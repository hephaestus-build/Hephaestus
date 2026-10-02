import { browser } from "@wxt-dev/browser";
import { defineBackground } from "wxt/utils/define-background";

import { restrictSessionStorage } from "~/background/storage";
import {
	handleMessage,
	onSiteAccessAdded,
	onSiteAccessRemoved,
	onTabRemoved,
	reconcile,
} from "~/background/worker";

/**
 * Every listener is registered synchronously on each start, because Chrome only delivers an event
 * that woke the worker to a listener that exists when the script finishes loading. Each handler then
 * awaits the storage it needs; nothing is kept in memory that a restart would lose silently.
 */
export default defineBackground(() => {
	void restrictSessionStorage();
	void reconcile();

	browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
		const reply = async () => {
			sendResponse(await handleMessage(message, sender));
		};
		void reply();
		// oxlint-disable-next-line typescript/strict-void-return -- Chrome keeps the reply channel open only when the listener returns `true`; its type says `void`.
		return true;
	});
	browser.permissions.onAdded.addListener(() => {
		void onSiteAccessAdded();
	});
	browser.permissions.onRemoved.addListener(() => {
		void onSiteAccessRemoved();
	});
	browser.tabs.onRemoved.addListener((tabId) => {
		void onTabRemoved(tabId);
	});
	browser.action.onClicked.addListener(() => {
		void browser.runtime.openOptionsPage();
	});
	browser.runtime.onInstalled.addListener(({ reason }) => {
		if (reason === "install") {
			void browser.runtime.openOptionsPage();
		}
	});
});
