import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";

import {
	AckPolicy,
	type ConsumerConfig,
	type ConsumerMessages,
	DeliverPolicy,
	type JsMsg,
	jetstream,
	jetstreamManager,
	ReplayPolicy,
} from "@nats-io/jetstream";
import { connect, type NatsConnection } from "@nats-io/transport-node";
import { Command, InvalidArgumentError, Option } from "commander";

import { isRecord, parseJson } from "./lib/json.ts";

const messageOf = (error: unknown): string =>
	error instanceof Error ? error.message : String(error);

type LogLevel = "debug" | "info" | "silent";

interface Logger {
	debug: (message: string) => void;
	info: (message: string) => void;
	error: (message: string) => void;
}

function createLogger(level: LogLevel): Logger {
	return {
		debug: (msg) => {
			if (level === "debug") {
				console.log(msg);
			}
		},
		info: (msg) => {
			if (level !== "silent") {
				console.log(msg);
			}
		},
		error: (msg) => console.error(msg),
	};
}

const REPO_ROOT = path.resolve(import.meta.dirname, "..");

const DEFAULT_NATS_SERVER = process.env.NATS_URL ?? "nats://localhost:4222";
const DEFAULT_EXAMPLES_DIR = path.join(REPO_ROOT, "server", "src", "test", "resources", "github");
const DEFAULT_NATS_SUBJECT = "github.HephaestusTest.>";
const DEFAULT_NATS_STREAM = "github";

function parsePositiveInt(value: string): number {
	const parsed = Number.parseInt(value, 10);
	if (!Number.isFinite(parsed) || parsed <= 0) {
		throw new InvalidArgumentError(`Expected a positive integer, got ${value}`);
	}
	return parsed;
}

function parsePositiveFloat(value: string): number {
	const parsed = Number.parseFloat(value);
	if (!Number.isFinite(parsed) || parsed <= 0) {
		throw new InvalidArgumentError(`Expected a positive number, got ${value}`);
	}
	return parsed;
}

function parseLogLevel(value: string): LogLevel {
	if (value === "debug" || value === "info" || value === "silent") {
		return value;
	}
	throw new InvalidArgumentError(`Invalid log level: ${value}`);
}

function parseIsoDate(value: string): Date {
	const trimmed = value.trim();
	if (!trimmed) {
		throw new InvalidArgumentError("Timestamp cannot be empty");
	}
	const hasOffset = /[+-]\d{2}:\d{2}$/u.test(trimmed);
	const normalized = trimmed.endsWith("Z") || hasOffset ? trimmed : `${trimmed}Z`;
	const parsed = new Date(normalized);
	if (Number.isNaN(parsed.getTime())) {
		throw new InvalidArgumentError(`Invalid ISO8601 timestamp: ${value}`);
	}
	return parsed;
}

function parseEventFilters(entries: string[]): Map<string, Set<string>> {
	const filters = new Map<string, Set<string>>();
	for (const entry of entries) {
		const [eventRaw, actionRaw] = entry.split(":", 2);
		const event = eventRaw?.trim().toLowerCase();
		if (event === undefined || event === "") {
			continue;
		}
		const existing = filters.get(event) ?? new Set<string>();
		if (actionRaw !== undefined) {
			const action = actionRaw.trim().toLowerCase();
			if (action) {
				existing.add(action);
			}
		}
		filters.set(event, existing);
	}
	return filters;
}

function getExampleFilename(eventType: string, action: string | undefined): string {
	return action === undefined ? `${eventType}.json` : `${eventType}.${action}.json`;
}

async function getExistingExamples(examplesDir: string): Promise<Set<string>> {
	try {
		const entries = await fs.readdir(examplesDir, { withFileTypes: true });
		return new Set(
			entries
				.filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
				.map((entry) => entry.name),
		);
	} catch {
		return new Set();
	}
}

function getMsgTimestamp(msg: JsMsg): Date | null {
	const timestamp = msg.time;
	return Number.isNaN(timestamp.getTime()) ? null : timestamp;
}

interface ExtractOptions {
	natsServer: string;
	examplesDir: string;
	natsSubject: string;
	natsStream: string;
	eventFilters: Map<string, Set<string>>;
	since: Date | null;
	until: Date | null;
	allowDuplicates: boolean;
	startWithNew: boolean;
	batchSize: number;
	fetchTimeoutMs: number;
	dryRun: boolean;
	logLevel: LogLevel;
}

function deliverPolicyFor(options: ExtractOptions) {
	if (options.startWithNew) {
		return DeliverPolicy.New;
	}
	return options.since === null ? DeliverPolicy.All : DeliverPolicy.StartTime;
}

async function extractWebhookExamples(options: ExtractOptions, logger: Logger) {
	logger.info(`Connecting to NATS server: ${options.natsServer}`);
	logger.info(`Output directory: ${options.examplesDir}`);
	logger.info(`Subject pattern: ${options.natsSubject}`);

	await fs.mkdir(options.examplesDir, { recursive: true });

	const existingExamples = await getExistingExamples(options.examplesDir);
	const extractedExamples = new Map<string, unknown>();
	const filenameCounts = new Map<string, number>();

	let processedCount = 0;
	let matchedCount = 0;
	let skippedByFilter = 0;
	let skippedByTime = 0;

	const decoder = new TextDecoder();

	/** Records one message as a new example, or says why it is not one. */
	function collectExample(msg: JsMsg): void {
		let payload: Record<string, unknown>;
		try {
			const parsed = parseJson(decoder.decode(msg.data));
			if (!isRecord(parsed)) {
				throw new TypeError("payload is not a JSON object");
			}
			payload = parsed;
		} catch {
			logger.info("Skipping invalid JSON message");
			return;
		}

		const subjectParts = msg.subject.split(".");
		if (subjectParts.length < 4) {
			logger.info(`Unexpected subject format: ${msg.subject}`);
			return;
		}

		const eventType = subjectParts[3];
		if (eventType === undefined || eventType === "") {
			logger.info(`Unexpected subject format: ${msg.subject}`);
			return;
		}
		const normalizedEventType = eventType.toLowerCase();
		const action =
			typeof payload.action === "string" && payload.action !== "" ? payload.action : undefined;

		if (options.eventFilters.size > 0) {
			const allowedActions = options.eventFilters.get(normalizedEventType);
			if (!allowedActions) {
				skippedByFilter += 1;
				return;
			}
			if (allowedActions.size > 0) {
				const normalizedAction = (action ?? "").toLowerCase();
				if (!allowedActions.has(normalizedAction)) {
					skippedByFilter += 1;
					return;
				}
			}
			matchedCount += 1;
		}

		const msgTimestamp = getMsgTimestamp(msg);
		if (options.since && msgTimestamp && msgTimestamp < options.since) {
			skippedByTime += 1;
			return;
		}
		if (options.until && msgTimestamp && msgTimestamp > options.until) {
			skippedByTime += 1;
			return;
		}

		let filename = getExampleFilename(eventType, action);
		if (options.allowDuplicates) {
			const baseName = filename.replace(/\.json$/u, "");
			const count = filenameCounts.get(baseName) ?? 0;
			let candidate = filename;
			let counter = count;
			while (existingExamples.has(candidate) || extractedExamples.has(candidate)) {
				counter += 1;
				candidate = `${baseName}.${counter}.json`;
			}
			filenameCounts.set(baseName, counter);
			filename = candidate;
		} else if (existingExamples.has(filename) || extractedExamples.has(filename)) {
			return;
		}

		extractedExamples.set(filename, payload);
		logger.debug(`Found new example: ${filename}`);
	}

	let nc: NatsConnection | null = null;
	let consumerName: string | null = null;
	let consumerRef: { delete: () => Promise<boolean> } | null = null;
	try {
		nc = await connect({ servers: options.natsServer });
		const js = jetstream(nc);
		const jsm = await jetstreamManager(nc);

		try {
			const streamInfo = await jsm.streams.info(options.natsStream);
			logger.info(
				`Stream info: ${streamInfo.state.messages} total messages in ${options.natsStream}`,
			);
		} catch (error) {
			logger.info(`Could not get stream info: ${messageOf(error)}`);
		}

		const deliverPolicy = deliverPolicyFor(options);

		if (deliverPolicy === DeliverPolicy.New) {
			logger.info("Consumer deliver policy: NEW (future messages only)");
		} else if (deliverPolicy === DeliverPolicy.StartTime && options.since) {
			logger.info(`Consumer deliver policy: START_TIME from ${options.since.toISOString()}`);
		} else {
			logger.info("Consumer deliver policy: ALL (full stream history)");
		}

		consumerName = `extract-examples-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
		const consumerConfig: ConsumerConfig = {
			name: consumerName,
			ack_policy: AckPolicy.Explicit,
			deliver_policy: deliverPolicy,
			replay_policy: ReplayPolicy.Instant,
			filter_subject: options.natsSubject,
		};
		if (deliverPolicy === DeliverPolicy.StartTime && options.since) {
			consumerConfig.opt_start_time = options.since.toISOString();
		}

		await jsm.consumers.add(options.natsStream, consumerConfig);
		const consumer = await js.consumers.get(options.natsStream, consumerName);
		consumerRef = consumer;

		logger.info(`Consumer created: ${consumerName}`);

		for (;;) {
			let msgs: ConsumerMessages;
			try {
				msgs = await consumer.fetch({
					max_messages: options.batchSize,
					expires: options.fetchTimeoutMs,
				});
			} catch (error) {
				const name = error instanceof Error ? error.name : undefined;
				const code = isRecord(error) ? error.code : undefined;
				if (name === "TimeoutError" || code === "TIMEOUT") {
					logger.info("No more messages available (timeout)");
					break;
				}
				throw error;
			}

			let gotAny = false;
			try {
				for await (const msg of msgs) {
					gotAny = true;
					processedCount += 1;
					collectExample(msg);
					msg.ack();
				}
			} catch (error) {
				logger.error(`Error fetching messages: ${messageOf(error)}`);
				break;
			}

			if (!gotAny) {
				logger.info("No more messages available");
				break;
			}
		}

		for (const [filename, payload] of extractedExamples.entries()) {
			const filepath = path.join(options.examplesDir, filename);
			await fs.writeFile(filepath, JSON.stringify(payload, null, 2));
		}

		logger.info("Extraction complete.");
		logger.info(`Processed: ${processedCount} messages`);
		if (options.eventFilters.size > 0) {
			logger.info(`Matched filter: ${matchedCount} messages`);
			if (skippedByFilter > 0) {
				logger.info(`Skipped by event filter: ${skippedByFilter} messages`);
			}
		}
		if (skippedByTime > 0) {
			logger.info(`Skipped by time window: ${skippedByTime} messages`);
		}
		logger.info(`Extracted: ${extractedExamples.size} new examples`);
		logger.info(`Total examples: ${existingExamples.size + extractedExamples.size}`);

		if (extractedExamples.size > 0) {
			logger.info("New examples created:");
			for (const filename of [...extractedExamples.keys()].toSorted()) {
				logger.info(` - ${filename}`);
			}
		}
	} finally {
		try {
			if (consumerRef) {
				await consumerRef.delete();
				logger.debug(`Consumer deleted: ${consumerName ?? "unknown"}`);
			}
		} catch {
			// A consumer that is already gone, or a connection that dropped first, is the state this
			// was reaching for. Whatever brought the run here is the failure to report.
		}
		if (nc) {
			await nc.close();
		}
	}
}

function buildProgram() {
	const program = new Command();
	program
		.name("nats-extract-examples")
		.description("Extract webhook examples from NATS JetStream")
		.option("--nats-server <url>", "NATS server URL", DEFAULT_NATS_SERVER)
		.option("--examples-dir <path>", "Output directory", DEFAULT_EXAMPLES_DIR)
		.option("--subject <subject>", "NATS subject pattern", DEFAULT_NATS_SUBJECT)
		.option("--stream <stream>", "NATS stream name", DEFAULT_NATS_STREAM)
		.option(
			"--event <event[:action]>",
			"Filter by event/action",
			(value, previous) => [...previous, value],
			[] as string[],
		)
		.option("--since <iso>", "Only include messages after this timestamp")
		.option("--until <iso>", "Only include messages before this timestamp")
		.option("--allow-duplicates", "Allow multiple examples per event/action", false)
		.option("--start-with-new", "Consume only new messages", false)
		.option("--batch-size <n>", "Batch size per fetch", parsePositiveInt, 50)
		.option("--fetch-timeout <sec>", "Fetch timeout in seconds", parsePositiveFloat, 5)
		.option("--dry-run", "Validate configuration and exit", false)
		.addOption(
			new Option("--log-level <level>", "Log verbosity")
				.choices(["debug", "info", "silent"])
				.default("info")
				.argParser(parseLogLevel),
		);
	return program;
}

async function main() {
	const program = buildProgram();
	program.parse(process.argv);
	const rawOptions = program.opts<{
		natsServer: string;
		examplesDir: string;
		subject: string;
		stream: string;
		event: string[];
		since?: string;
		until?: string;
		allowDuplicates: boolean;
		startWithNew: boolean;
		batchSize: number;
		fetchTimeout: number;
		dryRun: boolean;
		logLevel: LogLevel;
	}>();

	const logger = createLogger(rawOptions.logLevel);

	const { since: sinceText, until: untilText } = rawOptions;
	const hasSince = sinceText !== undefined && sinceText !== "";
	const hasUntil = untilText !== undefined && untilText !== "";
	if (rawOptions.startWithNew && (hasSince || hasUntil)) {
		throw new InvalidArgumentError("--start-with-new cannot be combined with --since/--until");
	}

	const since = hasSince ? parseIsoDate(sinceText) : null;
	const until = hasUntil ? parseIsoDate(untilText) : null;
	if (since && until && since > until) {
		throw new InvalidArgumentError("--since must be earlier than --until");
	}

	const options: ExtractOptions = {
		natsServer: rawOptions.natsServer,
		examplesDir: path.resolve(rawOptions.examplesDir),
		natsSubject: rawOptions.subject,
		natsStream: rawOptions.stream,
		eventFilters: parseEventFilters(rawOptions.event),
		since,
		until,
		allowDuplicates: rawOptions.allowDuplicates,
		startWithNew: rawOptions.startWithNew,
		batchSize: rawOptions.batchSize,
		fetchTimeoutMs: Math.round(rawOptions.fetchTimeout * 1000),
		dryRun: rawOptions.dryRun,
		logLevel: rawOptions.logLevel,
	};

	logger.debug(
		`Resolved options: ${JSON.stringify({
			natsServer: options.natsServer,
			examplesDir: options.examplesDir,
			natsSubject: options.natsSubject,
			natsStream: options.natsStream,
			eventFilters: [...options.eventFilters.entries()].map(([event, actions]) => ({
				event,
				actions: [...actions],
			})),
			since: options.since?.toISOString() ?? null,
			until: options.until?.toISOString() ?? null,
			allowDuplicates: options.allowDuplicates,
			startWithNew: options.startWithNew,
			batchSize: options.batchSize,
			fetchTimeoutMs: options.fetchTimeoutMs,
			dryRun: options.dryRun,
			logLevel: options.logLevel,
		})}`,
	);

	if (options.dryRun) {
		logger.info("Dry run enabled. Configuration validated successfully.");
		return;
	}

	await extractWebhookExamples(options, logger);
}

try {
	await main();
} catch (error) {
	console.error(`Webhook extraction failed: ${messageOf(error)}`);
	process.exit(1);
}
