# Flock demo — the systems-of-systems GIF

The one demo that needs all three projects at once: **conductor** (who is live),
**fathom** (who depends on what), **mcp-pact** (which change matters). A breaking
change in one repo reaches a live session in another — *before* the break lands.

Recorded at [`docs/flock.gif`](../docs/flock.gif); driven by
[`flock.sh`](flock.sh) / [`flock.tape`](flock.tape). Everything is real (the
daemon, the `FlockEngine`, a live `fathom serve` MCP subprocess, mcp-pact's
`SchemaShape` classifier); no API key. The two sessions are registered over the
same bus API a real Claude Code session uses.

## Reproduce

```
(cd ../fathom && mvn -q -DskipTests package)   # fathom jar, once
mvn -q -DskipTests package                      # conductor jar
FATHOM_JAR=../fathom/target/fathom.jar vhs demo/flock.tape
```

`flock.sh` builds the fixture in temp dirs (nothing committed): two git repos in
one fathom index, `service-a` publishing an `OrderRequest` DTO (a contract
surface) and `service-b` consuming it across the repo boundary.

## The story

```
service-a  ← session a3f2 (editing OrderRequest, the POST /orders request DTO)
service-b  ← session b7e1 (live, consuming it)

a3f2 adds a required field `promoCode` to OrderRequest (uncommitted).
   → conductor sees the pending change touches OrderRequest.java
   → fathom: resolve_file → Symbol:OrderRequest, marked contract surface (api-dto)
   → fathom: impacted_by → service-b consumes it (cross-repo `references` edge)
   → mcp-pact SchemaShape.diff → param.newRequired = BREAKING
   → registry: b7e1 is LIVE on service-b
   → b7e1's inbox, unprompted, before any commit:

     ⚠ FLOCK: session a3f2c118 is making a breaking change to service-a
       Symbol:OrderRequest (a contract service-b consumes via references) —
       param.newRequired (BREAKING). You are live on service-b, which depends
       on it. Coordinate before relying on the current shape.
```

Cross-repo, contract-scoped, live-session-targeted, fired on the *pending*
change. Flock never blocked anything — it informed the one session that would
break.

## Notes

- No timing claim; the point is the *unprompted cross-repo alert*, not speed.
- Clean-room: the fixture repos carry no employer identifiers.
- The three-way AND and every suppression path are also covered headless in
  `FlockEngineTest`.
