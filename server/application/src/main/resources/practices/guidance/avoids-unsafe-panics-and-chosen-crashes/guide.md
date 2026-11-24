## How to do it

- Before you ask for a review, find each crash operator that you added. Examples are `x!`, `try!`, `as!`, `fatalError`, `unwrap()`, `expect()`, `panic`, `!!` and `process.exit`.
- For each one, ask where its value comes from and where the code runs. A value from a user, the network, a database, or a file can be missing.
- On such a path, give the code a way out. Use `if let`, a default value, or an error that the caller can handle. For example, instead of `Int(ageText)!` on text a user typed, use `if let` and show an error when it fails.
- Keep a crash operator where a failure means the code is already broken. Examples are a value that you just checked or a fixed URL that you wrote. Keep one in a test, in a preview, or at a command-line or startup entry point.

## When it does not apply

- The lines that you added have no crash operator. Examples are a change to only docs, configuration or data, or to only generated or copied-in files.
- The only lines that can crash use no crash operator, such as an index into a list or a division. Another practice covers those.

It still applies when you guard the operator on the same path, such as behind `if let` or a nil check.

## Common mistakes

- Treating every `!` as a bug. What matters is whether a real input can make it fail. Instead, trace where the value comes from.
- Force-unwrapping the first item of a fetched list, such as `results.first!`. An empty result is normal. Instead, handle the empty case.
- Calling `fatalError` or `panic` when a network call fails. Requests fail in normal use. Instead, return the error so that the caller can react.
- Copying `try!` from a preview or a test into app code. Instead, catch the error and show the person a way to recover.

## Sources

- [To `panic!` or not to `panic!`](https://doc.rust-lang.org/book/ch09-03-to-panic-or-not-to-panic.html), Klabnik, Nichols and Krycho, The Rust Programming Language. The book says to return an error for expected failure, such as malformed data. It allows a panic in tests or where your code makes sure a value is valid.
- [The basics](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/thebasics/), The Swift Programming Language, Swift project. Force unwrap only when a missing value is a programmer error, and otherwise use optional binding or a fallback value.
- [Error handling](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/errorhandling/), The Swift Programming Language, Swift project. Use `try!` only when the call cannot throw, such as loading an image that ships with the app.
- [Go code review comments](https://go.dev/wiki/CodeReviewComments#dont-panic), the Go project. Do not use panic for normal error handling, and return an error instead.
