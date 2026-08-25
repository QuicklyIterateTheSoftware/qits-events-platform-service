# qits-events — working notes

Read `README.md` first: it defines the boundary and lists the routes. This file is the working
conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. Anything that would break that is not a tradeoff to weigh;
it is the thing this repo exists to avoid. That is why the poms duplicate versions instead of
inheriting them, and why the suites spawn their own PostgreSQL from a Maven artifact rather than
reaching for a container.

**The one thing it now needs besides Maven Central** is the platform's own Maven repository, for
`qits-db-core` and `qits-arch-rules` — the patient driver every connection opens through, and the
test that refuses to let the datasource baseline go missing. `<repositories>` in the root pom points
at `${qits.maven.repository.url}` (the developer-host address by default), and the image build
overrides it; see **Dependencies**. Two published jars is what the platform's cutover survival costs,
and it is the smallest form of it: neither has a copy that could live here instead.

**Which command is the gate depends on whether you have the client**, and this is worth getting
right because the platform reference states it loosely:

- `./mvnw test` — needs **neither node nor the webui submodule**, and no docker either. Quinoa is
  disabled by default in test mode (it says so: `Quinoa is disabled by default in tests.`), so all
  113 `@QuarkusTest`s pass against an empty `webui/` on a machine with no node at all — the stream
  socket included, since a websocket is not a Quinoa concern. The store they run on is a real
  postgres the suite spawns itself from a Maven artifact. Measured, not assumed.
- `./mvnw verify` — runs `package` on its way to failsafe, and `package` is where Quinoa augments.
  So verify needs **both**, and against an uninitialised submodule it fails with
  `No package.json found in Web UI directory: 'src/main/webui'`. `docs/project-setup-quinoa-angular.md`
  in the superproject says verify needs neither; that is true of the tests it runs and not of the
  goal, and it is true of every SPA-serving service, not a wart of this one.

**`service/` compiles to a GraalVM native image**, and it extends the clone-alone rule rather than
qualifying it: `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a `native-image` and
`./mvnw package -Dnative` produces `service/target/qits-events` with no container involved.

Two consequences worth stating before you reach for a dependency:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image …
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resource loading by computed name and JNI/JNA all need registering, and
  the failure lands at *runtime* in the binary while the JVM suite stays green. Prefer what is
  already in the image — `ProcessBuilder` over a process library, `java.lang.foreign` over JNA.

The one native-image trap this repo inherited by *not* doing it was the H2 `AUTO_SERVER=TRUE` flag:
a feature in a shipped datasource default whose class a native image loads by name and therefore does
not have, killing the binary in connection-pool warm-up while every JVM run stayed green. It cost
qits-ci and qits-projects a release each. **The lesson outlived the url** and now reads: every config
default the app boots with is part of the native surface. Today's datasource ships an *expression*
over `QITS_RESOURCE_DB_*` and no fallback url at all, so there is no longer a default with a feature
in it to lose — which is a smaller surface, not an exemption.

## Package and module conventions

`eu.wohlben.qits.events.*` across `events/` and `service/`, with disjoint sub-packages so there is
no split package, plus `eu.wohlben.qits.webui` for the bare-segment redirect (the same package name
its siblings use, so the file is recognisable across repos):

- `events/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the
  sense that matters: no JAX-RS. Entities are Panache active-record with public fields; mappers are
  MapStruct `@Mapper(componentModel = "jakarta")`; errors carry an HTTP status code so the web layer
  can map them without this module knowing what HTTP is.
- `service/` — `api` (JAX-RS + the exception mapper), `security` (the header-reading mechanism),
  `stream` (the event stream socket and the table of who is subscribed to what). `stream` sits here
  rather than in `events/` for the same reason `api` does — it needs a web stack — and it is split
  socket/registry the way qits-ci splits `CiDaemonSocket` from `CiDaemonRegistry`: the socket owns
  the lifecycle and the framing, the registry owns the subscription table and the fan-out.
- `webui/` — `WebUiRedirect`, and only that.

`control/` is flat and stays flat.

Controller request/response shapes are **nested records** on the controller
(`CreateEventRequest` / `CreateEventRequest.Response`): the wire contract for one operation lives
beside the method that serves it, and the generated OpenAPI document names them after the operation
rather than after a bag of shared DTOs.

## Paths

Everything is served under this service's gateway segment — see the table in the README. The thing
that is easy to get wrong:

**A new machine surface outside `/events/api` needs a line in
`quarkus.quinoa.ignored-path-prefixes`, in the same commit.** Quinoa's SPA fallback is a catch-all at
`/events/*` registered near-last, so a real route still wins — but a path matching *no* route is
rerouted to `index.html` and answers `200 text/html`, which a machine client parses as data. Three
facts about that key, all measured on sibling services:

- Setting it **replaces** Quinoa's derivation rather than extending it. The derivation reads
  `quarkus.rest.path` and `quarkus.http.non-application-root-path` and produces exactly `/api,/q` —
  which is why those two are repeated by hand in the key today. Naming a third alone would
  *un-ignore* both.
- The values are matched **after** `ui-root-path` is stripped, so they are **relative**.
  `/events/api` written there matches nothing at all and is indistinguishable from leaving the key
  unset — the failure that hides.
- `@WebSocket` and anything registered straight onto the Vert.x router do **not** follow
  `quarkus.rest.path`; they take a literal path and need their own entry. websockets-next claims
  only the upgrade handshake, so a plain GET on a socket path falls through to the SPA. That is why
  the key reads `/api,/q,/stream` today: `EventStreamSocket` is `@WebSocket(path = "/events/stream")`
  and the `/stream` entry landed in the same commit. Ignoring a prefix stops the SPA *reroute* and
  does not unregister the route — the upgrade still works, and `PackagedSurfaceIT` asserts both
  halves, because both are invisible to a `@QuarkusTest`.

The segment itself is spelled in **four** places that move together: `quarkus.quinoa.ui-root-path`,
`quarkus.rest.path`, `quarkus.http.non-application-root-path`, and the client's `baseHref` in
qits-spa-events' `angular.json` — the fourth in another repository, where no build here can check
it. A `baseHref` that disagrees yields a page that loads and then fetches its own JavaScript from
the wrong place, and no server-side test can see it.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `events/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name to record". A check of the
form `if (identity.isAnonymous()) deny` would look like a security control and be worth nothing,
because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`; human-facing REST boundaries use Jakarta
`@RolesAllowed("qits:admin")`. Machine-facing boundaries require an authenticated identity and
retain their narrower `MachineAuth` audience/scope checks.
is the entire reason a header can be trusted as an identity here.

`ForwardAuthTest` exercises the real header through the real mechanism rather than
`@TestSecurity`, on purpose. The header **is** the contract — nothing else ever produces a principal
in a deployed service — so an annotation that fabricates an identity proves a path the deployment
never takes. That is exactly how the bug ran unseen in qits-projects: it shipped a
`SecurityIdentity` with no mechanism behind it, every recorded principal was null, and the
annotation went on passing the whole time.

Do not lift `events/security` into a shared `libs/qits-auth`. Every repo builds from a clone of
itself alone, so ~115 lines duplicated per service is cheaper than a jar that has to travel to all of
them; the duplication is the decision, not an oversight.

## The bus

**One instance serves the whole platform.** `.config/qits/deployments.yml` says
`deployment_target: platform`, and the wire alias is the bare `qits-events` — no tier prefix — which
is what the eventstream library defaults to. Until 2026-08-17 each environment ran a broker of its
own, and that was the only scoping this service ever had: there are no topics here, routing is the
event signature plus each consumer's own watermark, so *which instance you dialled* was the whole
boundary. Nothing in this repo encoded a tier, which is why the flip is a declaration and not a
change of behaviour. The per-tier view the wall used to give for free came back as data:
`environment` on the envelope — the tier the publisher ran in (`dev`, or `platform` for a service
that serves every tier), stamped by the publisher from its own `QITS_ENVIRONMENT`, null for events
recorded before the platform knew tiers — with `?environment=` on the list route as the read model
(an indexed equality on its own column, `idx_event_environment`, unlike the payload scans).

`PUT /events/api/events/{id}` and `/events/stream` are the two surfaces that make this a bus rather
than a log, and the wire contract for both is frozen in `eventsourcing-plan.md` in the superproject.
Three things about it are load-bearing here:

- **`payload` is stored and compared verbatim.** It arrives as canonical JSON *inside a string*.
  Canonicalization happens in the publisher (qits-ci's `qits-eventsourcing` module); a server that
  reformatted the value — pretty-printing it, reordering keys, parsing and re-serializing it — would
  break the byte-for-byte equality the idempotent PUT rests on, and the break would look like
  "publishers keep getting 400 on their own retries".
- **The comparison is `name` + `occurredAt` + `payload` + `parentId` + `environment`, and
  `description` is outside it** on purpose. The line is *identity of the occurrence* versus *prose about it*: the human
  account is not part of an event's identity, and a cause is — it is machine-consumed structure and
  the edge a chain is drawn from, so two PUTs of one id claiming different parents are two different
  claims about history. Kept outside, the server would silently keep the first and answer 200 while
  the publisher believed it had published the second: two services disagreeing about the shape of
  history with no error anywhere. It costs a well-behaved publisher nothing, because an outbox
  stores the envelope whole and its own two attempts cannot disagree. `environment` sits on the
  identity side by the same argument: one id claiming two tiers is two claims about history. Its
  guard is shape rather than existence — a dns-safe name (`Validations.requireEnvironmentIfPresent`),
  never a lookup against qits-deployments' environments, which can delete an environment after its
  events truthfully happened. `occurredAt` is truncated to
  microseconds on the way in, because the column is `timestamp(6)` and comparing the caller's
  nanoseconds against the database's microseconds would 400 a publisher's honest retry.
- **`parentId` is validated twice and checked never.** It must be a canonical UUID when present, and
  it may not equal the event's own id — both decidable from a single row, so both 400. There is
  deliberately **no existence check and no FK**: nothing orders a parent's arrival before its
  child's, and 400 is unretryable, so a check would turn a publisher's timing accident into
  permanent data loss. A dangling parent is data. The reasoning is written where the check would
  otherwise live (`EventService.causeOf`) and beside the column in `V1__init.sql`; if a future change makes it
  look like an oversight, read those first.
- **The field is on the wire as an explicit `null`.** Absent-means-null is the contract's one
  backward-compatibility clause (an older publisher keeps working), but this service always *emits*
  the key — a consumer probes for it to learn whether this service knows about causation, so an
  omit-nulls Jackson customizer here would be a silent break. `PackagedSurfaceIT` pins it with
  `hasKey`, not `nullValue()`: an absent JSON path also reads as null, so `nullValue()` alone would
  go on passing through the break.
- **Only a *create* broadcasts.** A 200 replay pushes nothing — a subscriber must not see an event
  twice because a network dropped an acknowledgement. That is why the CDI signal is named
  `EventCreated`, fired from `EventService` (so both write paths cannot diverge about it) and
  observed `AFTER_SUCCESS` (so a rollback pushes nothing).

`EventCreated` carries `@RegisterForReflection`: it is serialized by Jackson directly rather than as
a JAX-RS return type, so nothing else tells the native-image builder its accessors are reachable. Without
it the JVM suite stays green and the binary pushes `{}`.

Its javadoc used to call the five components *and their order* the contract. The order clause is
retired — both sides bind by name, and the publishing library disables `FAIL_ON_UNKNOWN_PROPERTIES`
precisely so a subscriber built against five fields survives a sixth. The rule that replaced it is
**append**, so an old subscriber goes on reading the frame it always read.

The read model for causation is two things and stays two: `parentId` on `EventDto` (upwards, with
the `GET /{id}` that already exists) and `?parentId=` on the list route (downwards,
`EventRepository.listChildrenOf`, which is what `idx_event_parent_id` is for). A query parameter
rather than a route because a new literal under `/events` would need an
`ignored-path-prefixes` entry in the same commit — this feature is the one that needs none.

The fan-out never blocks and never throws upwards. It runs on the thread that completed the create's
transaction, so `sendTextAndAwait` — which is `sendText(…).await().indefinitely()` under a friendlier
name, the shape qits-ci banned by name — would let one dead subscriber hold a committed write's
thread forever. One broken socket costs its own frame and nothing else.

## Reading the log

The list route pages, and two of its properties are easy to undo by accident:

- **The sort is `(occurredAt desc, id desc)` and the id half is not decoration.** `occurredAt` is not
  unique and cannot be made unique — a pipeline run's events carry the run's finish instant, so a
  fork's siblings tie to the microsecond by construction. Dropping the tiebreaker makes two identical
  requests disagree about the order of a tied pair and makes every cursor over the list lossy.
  `EventRepository.NEWEST_FIRST` is the one sort, and `listChildrenOf` uses it too: a fork's children
  are the exact rows that tie.
- **The cursor is composite for that reason.** `?cursor=<occurredAt>,<id>`, predicate
  `occurred_at < :at or (occurred_at = :at and id < :id)`. A scalar `before=<occurredAt>` is the
  obvious shape and it splits a fork across a page boundary — it either repeats a sibling or drops
  one. If a future change makes the pair look like ceremony, read `EventCursor`.
- **`?order=asc` reads the same page forward, and both halves of the comparison flip with it:**
  `occurred_at > :at or (occurred_at = :at and id > :id)`, sorted `(occurredAt asc, id asc)`. The
  sort's id half has to turn round with its instant half — an ascending instant beside a descending
  id is still a total order and still skips rows inside a tie, which is the exact failure the
  composite cursor exists to prevent. `nextCursor` stays the page's last row in both directions, and
  that is what a durable consumer keeps as its **watermark**: it catches up by paging ascending from
  the last row it handled, which descending cannot express at all. An unreadable `order` is a 400
  naming the parameter, like every other filter — falling back to `desc` would answer a catch-up
  consumer with the head of the log and let it record a watermark it never reached. `EventOrder`
  holds the reasoning and the parsing.

`EventQuery` parses every filter, and the boundary hands it the caller's **text**: `limit`, `since`,
`q`, `cursor`, `order` and `name` are all `String` `@QueryParam`s, and `attr` is a repeatable `List<String>` of
the same unparsed text. That is deliberate — one place decides what a bad value means and every bad
value is a 400 whose message names the parameter, where a JAX-RS parameter converter answers 404 for
a query parameter it cannot convert, with no body worth reading. Blank is absent throughout, the rule
`?parentId=` already followed.

`?q=` is a substring of the payload and **parses nothing**. The payload is opaque here — that is what
makes the idempotent publish's byte-for-byte comparison true — and there is no single key meaning
"which repository" to project anyway (`repoId` on a build, `repository` on a release). A projected
column is the thing to refuse first if someone wants exactness.

`?attr=<key>=<value>`, repeatable and ANDed, is the exact question `?q=` cannot answer, without
projecting anything: `EventQuery.attrFiltersOf` builds one `lower(payload) like …` pattern per filter
matching the literal `"key":"value"`, **closing quote included** — so `attr=packageType=dae` does not
match a value of `daemon` — and `EventRepository.listPage` binds one such clause per filter beside the
`?q=` clause. It leans on `CanonicalJson`'s guarantee (alphabetically-sorted keys, string values
quoted) rather than on payload adjacency, so it stays exact even as fields are added around it; it is
honest only for **string-valued** keys of events published through `CanonicalJson`, and it is a scan
like `?q=`, with no migration, no index and no new route.

`GET /events/api/events/names` is a literal beside the `/{id}` template. JAX-RS sorts literal
characters ahead of a template so it wins, but that is a spec guarantee being leaned on, so
`EventApiTest` and `PackagedSurfaceIT` both assert it. Everything here stays under `/events/api`, so
`quarkus.quinoa.ignored-path-prefixes` is untouched — check that again before adding a route.

## The store

**It is PostgreSQL, and it is declared rather than configured.** `.config/qits/deployments.yml`
carries `resources: postgresql:db`; qits-platform-deployments creates the role and the database
(`qits_events` — the default derivation: the application name minus its `qits-` prefix, under a
`qits_` prefix) on the platform environment's postgres before the successor container starts, and injects
`QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD`. The events jar's shipped defaults expand exactly
those three names, and **nothing else**: there is no fallback url, so an unset variable leaves the
expression unresolvable and the process dies at Flyway naming the missing name rather than opening a
store nobody meant. That triple is the platform's *generic* contract — nothing in it is
events-specific, which is what keeps the deployer framework-agnostic.

## Schema changes

`events/src/main/resources/db/events/migration/`, hand-written, its own lineage on its own
datasource. Entities live in a **named** persistence unit (`events`), not the default one — there is
no default datasource in this app at all, which is why
`quarkus.hibernate-orm.events.packages` is set: without it an entity has no unit to belong to and
the boot fails naming neither.

**The lineage restarted at V1 when the store moved off H2.** The five H2 migrations were deleted
rather than continued, and that was a decision with one precondition: the move onto postgres is an
**unwrap and a re-bootstrap**, so no database anywhere was left on the old lineage and no
`V6__move_to_postgres.sql` would have had a reader. The fresh `V1__init.sql` is those five
translated — `clob` → `text`, the V2 and V3 columns declared in the table instead of added to it,
V5's index created beside V1's and V3's — minus V4, whose `delete from Event` removed three rows of
a database that no longer exists and would now only read as an instruction. The entity moved with
none of it: it names no `columnDefinition`, so there was nothing to keep in step. **A second clean
start is not a precedent** — it cost a re-bootstrap, and the ordinary rule (append, never edit an
applied migration) is back from V1 onward.

The table is `event`, unquoted. PostgreSQL folds an unquoted identifier to lower case and so does
Hibernate's naming strategy for the entity `Event`, so the two agree without a quote in either
place — where H2 folded both to upper case and agreed the other way.

## Dependencies

**`quarkus-undertow` must never be on the classpath.** Its presence breaks Quinoa's production
static serving — the client 404s from a build that was green — and it arrives *transitively* from
anything servlet-shaped. Check before adding anything that sounds like a web framework:

    ./mvnw -pl service -am dependency:tree | grep -i undertow

**Quinoa is in no BOM**, so its version is pinned by hand, in the root pom's properties
(`quinoa.version`) rather than beside the dependency. 2.8.2 is the last release built against a
Quarkus *older* than the platform's 3.34.6; 2.8.3 is built against 3.36.2, ahead of us. Bump only
when the platform's Quarkus passes the version a release is built against.

**The two platform jars, and the three files they made this repo grow.** `qits-db-core` is
**runtime** scope in `events/`, beside the `jdbc.driver` line that is the only thing naming it;
`qits-arch-rules` is **test** scope in `service/`, whose classpath is the deployable's whole config.
Both are published by qits-integrations-quarkus and version-pinned by a property each in the root
pom. Getting them into an image build took the qits-deployments arrangement, unchanged: a
`<repositories>` entry with the id `qits-maven`, `.qits-maven-settings.xml` mirroring exactly that id
onto `$QITS_MAVEN_REPOSITORY_URL` (an exact id match is what gets past Maven's `external:http:*`
blocker), and a `--build-arg` in `.config/qits/ci-post-receive.yml` deriving the address from
`$QITS_REGISTRY`. The docker build also moved to `--network host`, which buildkit needs to reach it.
The three move together — a new platform jar needs none of them again.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus reads main's copy during a test run and merges the test resources over it,
  so `quarkus.rest.path` and the rest are already in effect. Never re-declare them in
  `src/test/resources/application.properties`: a test copy is free to drift from the shipped one,
  and then a green suite proves nothing about what actually starts. That file is for genuine
  test-only overrides (the persistence-unit wiring, `clean-at-start`, the test port,
  `quarkus.devservices.enabled=false`).
- **No dev services and no containers, ever.** A dev service is a container start, and the first
  rule here is that a clone tests green with no docker. The store being postgres does not change
  that answer: `EmbeddedPg` starts **zonky's** postgres — real binaries resolved as Maven artifacts,
  spawned as a child process — and `EmbeddedPgConfigSource` hands its url, username and password to
  every `@QuarkusTest` at an ordinal above `application.properties`, because the port is chosen at
  run time and cannot be written into a file. Both are **copied** per module (`events`'
  `persistence/`, `service`'s `testdb/`) rather than shared: a test-jar dependency between two
  modules that have none is the higher price. Each module names its own database (`events_test`,
  `events_svc`) so two suites cannot mean the same one. Testcontainers is not on this classpath and
  must not arrive.
- **`quarkus.http.test-port=0`, deliberately.** Quarkus' default test port is 8081, which on the
  deployment host is the published address of the platform's own npm registry — so the default makes
  the entire suite fail with `Port already bound: 8081` on the one machine this repo is most likely
  to be built on. It also removes the `@QuarkusTest`-restart race the siblings carry as a documented
  flake. The same value is passed to failsafe in `service/pom.xml`.
- **`OpenApiSchemaExportTest` writes `docs/openapi.yml`** from `/events/q/openapi`. Regenerate and
  commit it whenever the REST surface changes:

      ./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest -Dsurefire.failIfNoSpecifiedTests=false

  It asserts nothing — the committed diff is the assertion, which is what makes an API change
  reviewable instead of something a caller meets at runtime. Two things it does not cover: the test
  classpath is indexed too, so a `@Path` resource under `src/test` lands in the document unless it
  is `@Operation(hidden = true)` (`IdentityEchoResource` and `LogProbeResource` both carry it), and
  `/events/stream` is a `@WebSocket`, which OpenAPI describes in no form at all.
- **`mvn verify` passing does not mean the app starts.** Augmentation runs per `@QuarkusTest`
  regardless of packaging, so a missing `quarkus-maven-plugin` goal is invisible to the suite — it
  happened in qits-projects, an `<executions>` block under a `<build>` whose `<testResources>` came
  first, and only a boot caught it. `<packaging>quarkus</packaging>` is what closes that hole: it
  binds the goals to the lifecycle, and removing `<extensions>true</extensions>` now fails with
  "Unknown packaging: quarkus" rather than quietly building nothing.
- **`PackagedSurfaceIT` is the only test that runs against the artifact, and the only one that ever
  sees the client.** Quinoa is disabled in test mode, so no `@QuarkusTest` here has a client in it at
  all — a unit test asserting anything about `/events/` would pass against a process serving nothing.
  Every `@QuarkusTest` also augments in the build JVM, with the whole classpath present, reflection
  unrestricted and a datasource handed to it by a config source; a native image has none of those.
  It runs under `-Dnative`, and `-DskipITs=false` runs it against the fast-jar:

      ./mvnw -B -ntp verify -DskipITs=false

  It hands the launched process `QITS_RESOURCE_DB_URL` and its two siblings — the generic contract a
  deployment supplies — rather than restating the datasource keys, so the jar's own `${…}`
  indirection is what is under test, and it reads the written row back over JDBC to prove which
  database the process really opened. `PackagedLogBridgeIT` does the same on a database of its own,
  because a process with no store dies at Flyway before it logs anything worth reading. Both reach
  their embedded postgres through a **system property**, because a `QuarkusTestProfile` is
  instantiated in more than one classloader and a static field is not shared between them.
- **The probe list is the platform's**, from `docs/project-setup-quinoa-angular.md` in the
  superproject, and any change touching the Quinoa setup re-runs it: `/events/` → 200 HTML with the
  right `<base href>`; a deep link → 200 `index.html`; `/events/api/<real>` → the API's own answer;
  `/events/api/nope` → 404 and **not the client**; every literal machine path, mistyped → 404.
  Note the last two are asserted as "404 and not `index.html`" rather than "404 and not HTML": what
  a mistyped path actually gets is Vert.x' own stock 53-byte `<h1>Resource not found</h1>`, which is
  `text/html` and correct. The content type alone cannot tell the two apart — `index.html` is
  `text/html` too — so the *absence of the client* is what is pinned.
- A `Failed to start quarkus` / `Port already bound` failure is the known flake — `@QuarkusTest`
  restarts racing for the test port. Re-run first; `test-port=0` is why it should not happen here.

## Application logs leave over OTLP

`org.jboss.logging.Logger` calls become OTLP log records through Quarkus' OpenTelemetry logging
handler, on the same exporter as traces and metrics. Nothing in this service's own code does that
work and nothing should — the extension **is** the logging library.

The arrangement is four keys in `application.properties`, and all four are spelled out even though
three are Quarkus' own defaults, because the integration is still marked **preview**: an upgrade
that flipped one would stop the platform's logging with a green build. `quarkus.otel.logs.level` is
the one that is not a default — it makes INFO the outbound floor, deliberately, while console
logging stays untouched. The OTel handler is an **additional** copy of every record, never the only
one; stdout is the fallback that survives the receiver being down.

`telemetry/OtlpLogStub` is the machinery, and it decodes rather than counts: a JDK `HttpServer` on
an ephemeral loopback port, wired in as `quarkus.otel.exporter.otlp.endpoint`, parsing each
`ExportLogsServiceRequest` (`io.opentelemetry.proto:opentelemetry-proto`, **test scope only** —
compile scope would drag protobuf into the native image for nothing). It also sets
`quarkus.otel.sdk.disabled=false`, because the shipped file turns the SDK off under `%test` so an
ordinary suite does not retry against an unresolvable `qits-observability`.

Three classes use it, and they answer different questions:

- `OtelLogBridgeTest` — the decision gate, in the build JVM. Identity, both timestamps, severity
  number *and* text, the formatted body, the throwable as `exception.type` / `exception.message` /
  `exception.stacktrace`, and an error logged inside a real server span carrying that request's
  trace and span ids. It also pins that the console handler is still attached beside the OTel one.
- `PackagedLogBridgeIT` — the same claim against the **artifact**, where the handler's runtime
  initialisation and protobuf marshalling are a different question. It runs the `prod` profile, so
  the shipped keys are what is under test, and it asserts on records the shipped code really makes:
  Quarkus' own startup INFO, and a genuine unhandled 500.
- `OtelLogExporterUnreachableTest` — the exporter pointed at a closed port. Requests keep answering,
  health stays UP, and 3000 records never block the caller.

Four things there were measured rather than assumed, and each one would have been wrong from memory:

- a record logged outside a span carries **absent** trace/span ids — an empty byte string, not a
  zero-filled one;
- `observedTime` is stamped, from a different clock than `time`, and lands microseconds either side
  of it — their order means nothing;
- the handler writes several more attributes (`bridge.name`, `code.function.name`,
  `code.line.number`, `log.logger.namespace`, `thread.name`, `thread.id`). They are incubating and
  deliberately not pinned;
- one failure makes several records at several levels. A predicate that matches a stack trace
  without also matching the severity picks Hibernate's WARN, not the ERROR an operator looks for.

## The image and the pipeline

`docker/Dockerfile` and `.config/qits/ci-post-receive.yml` are two halves of one thing, and the seam
between them is the only reason either is interesting: **the client cannot be built inside a docker
build.** It depends on `@qits/ui-components`, which lives only on the platform's own npm registry,
and a `RUN` step reaches the public internet but reaches that registry by no address at all. So the
pipeline step — which runs on `qits-net`, where it does resolve — installs and builds the bundle,
and the Dockerfile's builder stage neuters Quinoa's install/ci/build commands to `--version` and
packages what it was handed.

Three things follow, and each is load-bearing:

- **`.dockerignore` does NOT exclude the client's `dist/`.** That departs from the platform's Quinoa
  reference, which does — here `dist/` is the payload, and excluding it fails the build at the
  `test -f` guard. Every SPA-serving service in the platform carries the same departure.
- **The two `package-manager-install` flags exist only on the Dockerfile's `mvnw` line**, because the
  Mandrel builder image ships no node. They must never go into `application.properties`: a local or
  CI build must use the node on `PATH`, so that no build silently downloads a toolchain. `22.22.0` is
  the platform pin.
- **The bundle is `cp`'d onto itself before the build.** Quinoa *moves* `build-dir` rather than
  copying it, and overlayfs cannot rename a directory that still lives in a lower image layer — it
  answers EXDEV and the JDK's fallback refuses a non-empty directory, dying with
  `DirectoryNotEmptyException` seconds in. The `cp` re-materialises it in the layer that is about to
  move it, which is why it has to be in that same `RUN`.

The pipeline also rewrites `package-lock.json`'s `resolved` **origins** before `npm ci`: npm fetches
tarballs by the absolute URL in the lockfile and ignores the configured registry, and npm's own
`--replace-registry-host` is broken for a registry mounted under a path prefix. The committed
lockfile keeps the developer-host origin, which is correct locally.
