# Flock demo — the systems-of-systems GIF

The one demo that needs all three projects at once: **conductor** (who is live),
**fathom** (who depends on what), **mcp-pact** (which change matters). It shows a
breaking change in one repo reaching a live session in another — *before* the
break lands. Recording is the one manual step; the behavior itself is proven
end-to-end in `FlockEngineTest`.

## The story (what the GIF shows)

```
service-a  ← session A (editing OrderRequest.java, the POST /orders request DTO)
service-b  ← session B (editing the checkout flow, which calls POST /orders)

A adds a required field `promoCode` to OrderRequest.
   → conductor sees A's pending change touches OrderRequest.java
   → fathom: resolve_file → Symbol:OrderRequest, marked contract surface
   → fathom: impacted_by → service-b consumes it (cross-repo edge)
   → mcp-pact SchemaShape.diff → param.newRequired = BREAKING
   → registry: session B is LIVE on service-b
   → B's inbox, unprompted:

     ⚠ FLOCK: session A is making a breaking change to service-a
       Symbol:OrderRequest (a contract service-b consumes via references) —
       param.newRequired (BREAKING). You are live on service-b, which depends
       on it. Coordinate before relying on the current shape.

B and A coordinate. The break never lands.
```

## Setup (turnkey)

1. **Two repos + one fathom index.** Model them on fathom's
   `ImpactPrimitivesTest` fixture: `service-a` exposing `OrderRequest`/an
   `/orders` controller, `service-b` referencing `OrderRequest`. A
   `fathom.flock.yaml` with both as `sources` and the `code` pack:

   ```yaml
   workspace: flock-demo
   db: build/flock-demo.db
   packs: [code]
   sources:
     - { connector: git, path: ./service-a }
     - { connector: git, path: ./service-b }
   ```

   ```
   java -jar /abs/fathom.jar index --config fathom.flock.yaml
   ```

2. **Turn Flock on** in `~/.conductor/flock.properties`:

   ```properties
   enabled=true
   fathom_cmd=java -jar /abs/fathom.jar serve --config /abs/fathom.flock.yaml
   throttle_minutes=10
   additive=false
   ```

3. **Consent both projects** (Flock inspects A and tells B only with consent):

   ```
   conductor observe ./service-a
   conductor observe ./service-b
   conductor init ./service-a && conductor init ./service-b
   ```

4. **Two real sessions.** Start Claude Code in `service-a` (session A) and
   `service-b` (session B). Confirm `conductor flock` shows `fathom: reachable`
   and `conductor ps` shows both sessions + the flock banner.

5. **The change.** In session A, edit `OrderRequest.java` to add a required
   `String promoCode` component. A's `PostToolUse` fires the evaluation.

6. **The catch.** In session B, `inbox` shows the flock alert. Coordinate; if
   the contract was already discussed, `snooze` the entity.

## Recording notes

- Record keyless where possible (both agents can be scripted leaf processes, as
  in `war-story.tape` / `assist.tape`, with a real daemon + real `fathom serve`).
- Make **no timing claim**; the point is the *unprompted cross-repo alert*, not
  speed.
- Keep the clean-room rule: no employer identifiers in the fixture repos.
