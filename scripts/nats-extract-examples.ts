import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";

import {
	AckPolicy,
	type Consumer,
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

import { isSet } from "./lib/env.ts";
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
		if (!isSet(event)) {
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

interface ParsedMessage {
	payload: Record<string, unknown>;
	eventType: string;
	action: string | undefined;
}

const decoder = new TextDecoder();

/** The example a message carries, or nothing with the reason logged. */
function parseMessage(msg: JsMsg, logger: Logger): ParsedMessage | undefined {
	let payload: Record<string, unknown>;
	try {
		const parsed = parseJson(decoder.decode(msg.data));
		if (!isRecord(parsed)) {
			throw new TypeError("payload is not a JSON object");
		}
		payload = parsed;
	} catch {
		logger.info("Skipping invalid JSON message");
		return undefined;
	}
	const eventType = msg.subject.split(".")[3];
	if (!isSet(eventType)) {
		logger.info(`Unexpected subject format: ${msg.subject}`);
		return undefined;
	}
	const action =
		typeof payload.action === "string" && payload.action !== "" ? payload.action : undefined;
	return { payload, eventType, action };
}

function withinWindow(msg: JsMsg, since: Date | null, until: Date | null): boolean {
	const timestamp = getMsgTimestamp(msg);
	if (timestamp === null) {
		return true;
	}
	return !(since && timestamp < since) && !(until && timestamp > until);
}

/** Collects the examples a stream yields, keeping the counts the final report needs. */
class ExampleCollector {
	readonly extracted = new Map<string, unknown>();
	private readonly filenameCounts = new Map<string, number>();
	private readonly options: ExtractOptions;
	private readonly existing: Set<string>;
	private readonly logger: Logger;
	private processed = 0;
	private matched = 0;
	private skippedByFilter = 0;
	private skippedByTime = 0;

	constructor(options: ExtractOptions, existing: Set<string>, logger: Logger) {
		this.options = options;
		this.existing = existing;
		this.logger = logger;
	}

	collect(msg: JsMsg): void {
		this.processed += 1;
		const parsed = parseMessage(msg, this.logger);
		if (parsed === undefined) {
			return;
		}
		if (!this.passesFilter(parsed.eventType, parsed.action)) {
			this.skippedByFilter += 1;
			return;
		}
		this.matched += 1;
		if (!withinWindow(msg, this.options.since, this.options.until)) {
			this.skippedByTime += 1;
			return;
		}
		const filename = this.unusedFilename(getExampleFilename(parsed.eventType, parsed.action));
		if (filename === undefined) {
			return;
		}
		this.extracted.set(filename, parsed.payload);
		this.logger.debug(`Found new example: ${filename}`);
	}

	private passesFilter(eventType: string, action: string | undefined): boolean {
		const { eventFilters } = this.options;
		if (eventFilters.size === 0) {
			return true;
		}
		const allowedActions = eventFilters.get(eventType.toLowerCase());
		if (!allowedActions) {
			return false;
		}
		return allowedActions.size === 0 || allowedActions.has((action ?? "").toLowerCase());
	}

	/** The name to write under, or nothing when the example already exists and duplicates are off. */
	private unusedFilename(filename: string): string | undefined {
		const taken = (candidate: string) =>
			this.existing.has(candidate) || this.extracted.has(candidate);
		if (!this.options.allowDuplicates) {
			return taken(filename) ? undefined : filename;
		}
		const baseName = filename.replace(/\.json$/u, "");
		let counter = this.filenameCounts.get(baseName) ?? 0;
		let candidate = filename;
		while (taken(candidate)) {
			counter += 1;
			candidate = `${baseName}.${counter}.json`;
		}
		this.filenameCounts.set(baseName, counter);
		return candidate;
	}

	report(): void {
		const { logger } = this;
		logger.info("Extraction complete.");
		logger.info(`Processed: ${this.processed} messages`);
		if (this.options.eventFilters.size > 0) {
			logger.info(`Matched filter: ${this.matched} messages`);
			if (this.skippedByFilter > 0) {
				logger.info(`Skipped by event filter: ${this.skippedByFilter} messages`);
			}
		}
		if (this.skippedByTime > 0) {
			logger.info(`Skipped by time window: ${this.skippedByTime} messages`);
		}
		logger.info(`Extracted: ${this.extracted.size} new examples`);
		logger.info(`Total examples: ${this.existing.size + this.extracted.size}`);
		if (this.extracted.size > 0) {
			logger.info("New examples created:");
			for (const filename of [...this.extracted.keys()].toSorted()) {
				logger.info(` - ${filename}`);
			}
		}
	}
}

function isFetchTimeout(error: unknown): boolean {
	const name = error instanceof Error ? error.name : undefined;
	const code = isRecord(error) ? error.code : undefined;
	return name === "TimeoutError" || code === "TIMEOUT";
}

/** Feeds every message the consumer yields to the collector until the stream runs dry. */
async function drain(
	consumer: Pick<Consumer, "fetch">,
	options: ExtractOptions,
	collector: ExampleCollector,
	logger: Logger,
): Promise<void> {
	for (;;) {
		let msgs: ConsumerMessages;
		try {
			msgs = await consumer.fetch({
				max_messages: options.batchSize,
				expires: options.fetchTimeoutMs,
			});
		} catch (error) {
			if (isFetchTimeout(error)) {
				logger.info("No more messages available (timeout)");
				return;
			}
			throw error;
		}

		let gotAny = false;
		try {
			for await (const msg of msgs) {
				gotAny = true;
				collector.collect(msg);
				msg.ack();
			}
		} catch (error) {
			logger.error(`Error fetching messages: ${messageOf(error)}`);
			return;
		}

		if (!gotAny) {
			logger.info("No more messages available");
			return;
		}
	}
}

function consumerConfigFor(options: ExtractOptions, name: string, logger: Logger): ConsumerConfig {
	const deliverPolicy = deliverPolicyFor(options);
	if (deliverPolicy === DeliverPolicy.New) {
		logger.info("Consumer deliver policy: NEW (future messages only)");
	} else if (deliverPolicy === DeliverPolicy.StartTime && options.since) {
		logger.info(`Consumer deliver policy: START_TIME from ${options.since.toISOString()}`);
	} else {
		logger.info("Consumer deliver policy: ALL (full stream history)");
	}
	const consumerConfig: ConsumerConfig = {
		name,
		ack_policy: AckPolicy.Explicit,
		deliver_policy: deliverPolicy,
		replay_policy: ReplayPolicy.Instant,
		filter_subject: options.natsSubject,
	};
	if (deliverPolicy === DeliverPolicy.StartTime && options.since) {
		consumerConfig.opt_start_time = options.since.toISOString();
	}
	return consumerConfig;
}

async function extractWebhookExamples(options: ExtractOptions, logger: Logger) {
	logger.info(`Connecting to NATS server: ${options.natsServer}`);
	logger.info(`Output directory: ${options.examplesDir}`);
	logger.info(`Subject pattern: ${options.natsSubject}`);

	await fs.mkdir(options.examplesDir, { recursive: true });
	const collector = new ExampleCollector(
		options,
		await getExistingExamples(options.examplesDir),
		logger,
	);

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

		consumerName = `extract-examples-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
		await jsm.consumers.add(options.natsStream, consumerConfigFor(options, consumerName, logger));
		const consumer = await js.consumers.get(options.natsStream, consumerName);
		consumerRef = consumer;
		logger.info(`Consumer created: ${consumerName}`);

		await drain(consumer, options, collector, logger);

		for (const [filename, payload] of collector.extracted.entries()) {
			const filepath = path.join(options.examplesDir, filename);
			await fs.writeFile(filepath, JSON.stringify(payload, null, 2));
		}
		collector.report();
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
	const hasSince = isSet(sinceText);
	const hasUntil = isSet(untilText);
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
