# Ticket office fixture

Public, deterministic source for the GraphHarness demonstration. The Java inventory
and quote are independent of the small TypeScript formatting and Python reporting
examples. The graph must not invent runtime calls between these languages.

Run the Java checks with Java 21, writing only disposable outputs:

```sh
mkdir -p /tmp/graphharness-ticket-office-classes
javac -d /tmp/graphharness-ticket-office-classes java/*.java
java -cp /tmp/graphharness-ticket-office-classes tickets.TicketOfficeTest
```

Read-only agent tasks:

- Agent A: use the graph to locate `TicketInventory.reserve`, read its source, and
  explain what happens when the request exceeds remaining capacity.
- Agent B: locate `PriceQuote.totalCents`, read its source, and explain how integer
  overflow is handled.

These tasks prove observed navigation only. The separate `scripts/coordinated_agent_demo.py` creates a disposable Counter fixture for actual-agent reservation/edit contention; it does not modify this example. Language
capabilities are reported by the daemon, not inferred from these filenames.

For a disposable, recorded repair with two real Codex clients, use
`scripts/record_demo.py`. The script copies this public fixture before changing it;
its reservation timing is deliberately orchestrated. It requires an authenticated
Codex CLI and Playwright with Chromium. See its `--help` and the release ledger for
the actual run and configured model.
