import assert from "node:assert/strict";
import test from "node:test";

import { ARCHITECTURES, encoded, program } from "./tools-seccomp.ts";

/** Runs the filter on one call, as the kernel does. */
function verdict(
	architecture: keyof typeof ARCHITECTURES,
	call: { audit: number; number: number; family: number },
): number {
	const instructions = program(architecture);
	let accumulator = 0;
	for (let at = 0; at < instructions.length;) {
		const [code, jumpIfTrue, jumpIfFalse, value] = instructions[at] ?? [0, 0, 0, 0];
		if (code === 0x20) {
			accumulator = { 0: call.number, 4: call.audit, 16: call.family }[value] ?? 0;
			at += 1;
		} else if (code === 0x15) {
			at += 1 + (accumulator === value ? jumpIfTrue : jumpIfFalse);
		} else if (code === 0x35) {
			at += 1 + (accumulator >= value ? jumpIfTrue : jumpIfFalse);
		} else {
			return value;
		}
	}
	throw new Error("the filter ran off its end");
}

const ALLOW = 0x7f_ff_00_00;
const KILL = 0x80_00_00_00;
const NO_FAMILY = 0x00_05_00_00 + 97;
const NOT_PERMITTED = 0x00_05_00_00 + 1;

for (const architecture of ["x86_64", "aarch64"] as const) {
	const { audit, socket, ioUringSetup } = ARCHITECTURES[architecture];
	void test(`on ${architecture} only a Unix socket opens`, () => {
		assert.equal(verdict(architecture, { audit, number: socket, family: 1 }), ALLOW);
		for (const family of [2, 10, 16, 17]) {
			assert.equal(verdict(architecture, { audit, number: socket, family }), NO_FAMILY);
		}
	});

	void test(`on ${architecture} io_uring is refused, and other calls pass`, () => {
		assert.equal(verdict(architecture, { audit, number: ioUringSetup, family: 0 }), NOT_PERMITTED);
		assert.equal(verdict(architecture, { audit, number: 0, family: 2 }), ALLOW);
	});

	void test(`on ${architecture} a call of another architecture ends the process`, () => {
		assert.equal(verdict(architecture, { audit: 0x40_00_00_03, number: socket, family: 2 }), KILL);
	});
}

void test("a call by the x32 numbers ends the process", () => {
	const { audit, socket } = ARCHITECTURES.x86_64;
	assert.equal(verdict("x86_64", { audit, number: 0x40_00_00_00 + socket, family: 2 }), KILL);
});

void test("each instruction is eight bytes, in the kernel's order", () => {
	const bytes = encoded([[0x15, 1, 2, 0xc0_00_00_b7]]);
	assert.deepEqual([...bytes], [0x15, 0, 1, 2, 0xb7, 0, 0, 0xc0]);
});
