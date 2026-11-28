import type {
	SupportShare,
	SupportShareMessage,
	SupportSharePreview,
	SupportShareReceived,
	SupportShareTurn,
} from "@/api/types.gen";
import { daysAfter, daysBefore, hoursBefore, minutesBefore } from "@/stories/story-clock";

/** One failed question, as its diagnostics name it: no words of the question or the answer. */
export const failedTurn: SupportShareTurn = {
	turnId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b70",
	runId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b71",
	workspaceSlug: "acme",
	surface: "APP",
	status: "FAILED",
	attempt: 2,
	failureReason: "MODEL_UNAVAILABLE",
	failedCall: "chat.completion",
	failedCallStatus: 503,
	queuedAt: minutesBefore(12),
	startedAt: minutesBefore(12),
	finishedAt: minutesBefore(10),
};

export const answeredTurn: SupportShareTurn = {
	...failedTurn,
	status: "DONE",
	attempt: 1,
	failureReason: undefined,
	failedCall: undefined,
	failedCallStatus: undefined,
};

export const sharedMessages: SupportShareMessage[] = [
	{
		entryId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b80",
		author: "QUESTION",
		text: "Why does my build fail on the second run?",
		createdAt: minutesBefore(12),
	},
	{
		entryId: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b81",
		author: "ANSWER",
		text: "Your test reads a real host, so the second run waits for DNS.",
		createdAt: minutesBefore(11),
	},
];

export const recipients = [
	{ accountId: "7", displayName: "Ada Lovelace" },
	{ accountId: "9", displayName: "Grace Hopper" },
];

export const diagnosticsPreview: SupportSharePreview = {
	turn: failedTurn,
	messages: [],
	recipients,
	validForDays: 7,
};

export const textPreview: SupportSharePreview = {
	turn: answeredTurn,
	messages: sharedMessages,
	textDigest: "5f1c9c0a3b1e4d2f8a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f",
	recipients,
	validForDays: 7,
};

export const activeShare: SupportShare = {
	id: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b90",
	turnId: failedTurn.turnId,
	workspaceSlug: "acme",
	recipient: { accountId: "7", displayName: "Ada Lovelace" },
	includesText: true,
	state: "ACTIVE",
	createdAt: hoursBefore(3),
	expiresAt: daysAfter(6),
	readCount: 2,
	lastReadAt: hoursBefore(1),
};

export const ownShares: SupportShare[] = [
	activeShare,
	{
		...activeShare,
		id: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b91",
		includesText: false,
		state: "REVOKED",
		createdAt: daysBefore(2),
		expiresAt: daysAfter(5),
		revokedAt: daysBefore(1),
		readCount: 0,
		lastReadAt: undefined,
	},
	{
		...activeShare,
		id: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b92",
		recipient: { accountId: "9", displayName: "Grace Hopper" },
		state: "EXPIRED",
		createdAt: daysBefore(9),
		expiresAt: daysBefore(2),
		readCount: 1,
		lastReadAt: daysBefore(8),
	},
];

/** A share as its recipient reads it: who shared it, the diagnostics and the messages. */
export const receivedShare: SupportShareReceived = {
	id: activeShare.id,
	sharedBy: "Katherine Johnson",
	workspaceSlug: "acme",
	includesText: true,
	createdAt: activeShare.createdAt,
	expiresAt: activeShare.expiresAt,
	turn: failedTurn,
	messages: sharedMessages,
	textWithheld: false,
};

export const receivedShares: SupportShareReceived[] = [
	{ ...receivedShare, turn: undefined, messages: [] },
	{
		...receivedShare,
		id: "0198f5a2-7c4e-7a10-8f3b-2d6c1e9a4b93",
		sharedBy: "Alan Turing",
		includesText: false,
		turn: undefined,
		messages: [],
		createdAt: daysBefore(1),
		expiresAt: daysAfter(6),
	},
];
