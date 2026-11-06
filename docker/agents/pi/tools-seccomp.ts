// tools-seccomp.ts — the system call filter of a review's tools and programs: no socket but a Unix one.
//
// They reach the loop on a Unix socket and need nothing else, so an untrusted command has no way to the sandbox gateway,
// which shares their Pod's network. `setpriv --seccomp-filter` loads the file before the program starts; the filter then
// holds for each process that it starts. `io_uring` can open a socket without the `socket` call, so it goes too.

import { writeFileSync } from "node:fs";
import path from "node:path";

const AF_UNIX = 1;
const EPERM = 1;
const EAFNOSUPPORT = 97;

const RET_ALLOW = 0x7f_ff_00_00;
const RET_ERRNO = 0x00_05_00_00;
const RET_KILL_PROCESS = 0x80_00_00_00;

const LOAD_WORD = 0x20;
const JUMP_EQUAL = 0x15;
const JUMP_AT_LEAST = 0x35;
const RETURN = 0x06;

/** Offsets in `struct seccomp_data`; little-endian, so the low word of the first argument comes first. */
const NUMBER_AT = 0;
const ARCHITECTURE_AT = 4;
const FIRST_ARGUMENT_AT = 16;

/** The x32 ABI of x86-64 numbers its calls from here, so a call past it would miss the numbers below. */
const X32_CALLS = 0x40_00_00_00;

export const ARCHITECTURES = {
	x86_64: { audit: 0xc0_00_00_3e, socket: 41, ioUringSetup: 425 },
	aarch64: { audit: 0xc0_00_00_b7, socket: 198, ioUringSetup: 425 },
} as const;

type Instruction = [code: number, jumpIfTrue: number, jumpIfFalse: number, value: number];

/** The filter for one architecture. Jumps count instructions after the next one; a call from another ABI ends the process. */
export function program(architecture: keyof typeof ARCHITECTURES): Instruction[] {
	const { audit, socket, ioUringSetup } = ARCHITECTURES[architecture];
	return [
		/* 0 */ [LOAD_WORD, 0, 0, ARCHITECTURE_AT],
		/* 1 */ [JUMP_EQUAL, 0, 10, audit],
		/* 2 */ [LOAD_WORD, 0, 0, NUMBER_AT],
		/* 3 */ [JUMP_AT_LEAST, 8, 0, X32_CALLS],
		/* 4 */ [JUMP_EQUAL, 2, 0, socket],
		/* 5 */ [JUMP_EQUAL, 5, 0, ioUringSetup],
		/* 6 */ [RETURN, 0, 0, RET_ALLOW],
		/* 7 */ [LOAD_WORD, 0, 0, FIRST_ARGUMENT_AT],
		/* 8 */ [JUMP_EQUAL, 1, 0, AF_UNIX],
		/* 9 */ [RETURN, 0, 0, RET_ERRNO + EAFNOSUPPORT],
		/* 10 */ [RETURN, 0, 0, RET_ALLOW],
		/* 11 */ [RETURN, 0, 0, RET_ERRNO + EPERM],
		/* 12 */ [RETURN, 0, 0, RET_KILL_PROCESS],
	];
}

/** `struct sock_filter` for each instruction: code, two jumps and a value, little-endian. */
export function encoded(instructions: readonly Instruction[]): Buffer {
	const bytes = Buffer.alloc(instructions.length * 8);
	for (const [index, [code, jumpIfTrue, jumpIfFalse, value]] of instructions.entries()) {
		bytes.writeUInt16LE(code, index * 8);
		bytes.writeUInt8(jumpIfTrue, index * 8 + 2);
		bytes.writeUInt8(jumpIfFalse, index * 8 + 3);
		bytes.writeUInt32LE(value, index * 8 + 4);
	}
	return bytes;
}

if (import.meta.main) {
	const directory = process.argv[2] ?? ".";
	for (const architecture of ["x86_64", "aarch64"] as const) {
		writeFileSync(
			path.join(directory, `tools-seccomp-${architecture}.bpf`),
			encoded(program(architecture)),
		);
	}
}
