#### 🔴 Remove `interactiveReserve` from call pool requests

A call pool's limit is now learned from the provider, and that limit sets the conversation reserve.
`interactiveReserve` no longer exists in `/api/admin/llm/call-pools` requests or responses, and the database drops the column.
Change any script that sends or reads this field.

The upgrade gives every model without a pool a pool of its own, which starts at 4 calls in flight.
A pool's existing `maxConcurrentCalls` stays as a ceiling on the learned limit, and that pool also starts at 4 calls in flight.
Its limit rises while the provider serves all calls.
[Sharing provider capacity](/admin/ai-providers#6-sharing-provider-capacity) tells what you can still set.
