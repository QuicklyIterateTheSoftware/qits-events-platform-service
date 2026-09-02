# qits-events as a GraalVM-native container IMAGE — the same shape as every other qits service's
# docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal runtime stage carries
# only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names
# 25.0.2-graalce, so `sdk env && ./mvnw package -Dnative` produces service/target/qits-events
# locally with no container involved. What this adds is the image around it — and a way for a
# machine with no GraalVM to get a binary at all, since the builder stage brings its own
# native-image.
#
# Build from the repo root:
#
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/events:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the events
# client (service/src/main/webui = the qits-spa-events submodule), and the builder stage below
# packages a bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the
# mvnw line for why the build cannot happen inside that stage.
#
# --- `docker run` with no configuration FAILS, and that is the honest behaviour -------------------
# The `events` jar's datasource is an EXPRESSION over the platform's generic resource contract —
# `jdbc.url=${QITS_RESOURCE_DB_URL}` and its username/password siblings — with no fallback url at
# all. Unset, the expressions are unresolvable and the process dies during Flyway's first connect
# naming the missing variable.
#
# That is a missing-configuration failure, not a packaging one, and it is deliberately NOT patched
# here with an `ENV`: no address this image could name would be right on a real platform, and a
# default would only make the bare run "work" against a store nobody meant. What a deployment
# supplies instead is the triple:
#
#     QITS_RESOURCE_DB_URL=jdbc:postgresql://<tier>-qits-oci-postgresql:5432/qits_events
#     QITS_RESOURCE_DB_USERNAME=qits_events
#     QITS_RESOURCE_DB_PASSWORD=…
#
# and nobody types those: `.config/qits/deployments.yml` declares `resources: postgresql:db`, and
# qits-platform-deployments creates the role and the database before this container starts and
# injects all three. This image carries no volume for data any more — the store is another
# container's.
#
# Also not supplied: `qits.observability.url`, which ships as `http://qits-observability:8080` — the
# service name on qits-net, correct for this image out of the box. On a network where that name does
# not resolve the process still serves; the exporter simply logs a warning per attempt.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds service's dependency from source —
# `qits-events-domain` is a version this repo never publishes, so a builder container with an empty
# ~/.m2 has nowhere else to get it. Copying module by module would mean editing this file every time
# one is added, and forgetting to would fail the build with an unresolved dependency rather than
# anything that names the real cause. `.dockerignore` is what keeps this cheap.
COPY . .

# -Dnative flips quarkus.native.enabled (service/pom.xml carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests skips execution, not compilation: test sources still compile here. Running them is
# what is not possible in this stage — PackagedSurfaceIT (which the `native` profile un-skips)
# launches the binary against a datasource. `./mvnw verify` on a clone is the gate for those.
#
# --- the Quinoa flags, and why this stage does not build the client -------------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a local or CI `mvnw` build must use the node on PATH, so that no
# build silently downloads a toolchain. 22.22.0 is the PLATFORM pin
# (docs/project-setup-quinoa-angular.md in the qits superproject).
#
# The three command overrides are the load-bearing part, and the reason this file deviates from that
# recipe (which has the builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-events, and it depends on @qits/ui-components. That package
#   exists ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a docker build
#   reaches the public internet — Maven Central, npmjs.org, nodejs.org — and can reach the platform
#   registry by NO address: not the qits-net alias, not registry.dev.localhost:8080, not
#   host.docker.internal. So the install cannot happen here, and an install that "worked" here would
#   have resolved the @qits scope against npmjs.org, where that name does not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore, which for that
# reason does NOT exclude it — the one place this repo's ignore file departs from the reference
# recipe). `--version` is the null command: it proves the package manager is really there and does
# nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. (The same
# guard covers an uninitialised submodule, which git leaves as an empty directory and Quinoa rejects
# with `No package.json found in Web UI directory`.)
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-events/browser`, which names the source and explains
# nothing. A builder stage that *creates* dist itself is immune by accident; ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
# --- and where the two qits jars come from --------------------------------------------------------
# This repo depends on qits-db-core (PatientPgDriver) and qits-arch-rules, which live only on the
# platform's own Maven repository. Unlike the npm registry above, that one IS reachable from a build:
# the CI recipe passes the address the step container can use, and `.qits-maven-settings.xml` mirrors
# the `qits-maven` repository id onto it — an exact id match, which is what gets past Maven's
# external:http:* blocker without permitting arbitrary HTTP repositories. The ARG default is the
# developer-host address, so a plain host-side `docker build` still works.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates, once that repository stops answering anonymously ----------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# Absent secrets leave both variables unset, which is the state every build is in today and the
# state a host-side `docker build` is in permanently: Maven sends credentials only when a repository
# answers 401, and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    d=service/src/main/webui/dist/qits-spa-events/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts-service's first SBOM
# release (2026.902.45658): the service module skips maven-deploy — it ships as this image, not as a
# jar — and the plugin SKIPS deploy-skipping modules by default, with a green build and one INFO
# line. The sbom stage's COPY then fails on a file nothing wrote. The property spelling matters too:
# the bare -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the
# plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document — `service`, the module that becomes the binary, not
# `events`, which is a library jar this image never ships alone. Exported so the release step can
# PUT it to the platform's SBOM store. A stage rather than a layer of the runtime image, and BEFORE
# the runtime stage on purpose: the last stage is what a plain `docker build` builds, and that must
# stay the image. The release step reaches this one with `--target sbom --output type=local` using
# the SAME args and secrets as the image build, so every layer above is a cache hit and the export
# costs seconds. `makeBom` rides on the mvnw invocation above rather than a second one, for the same
# cache reason — one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# Nothing is installed here. Unlike qits-projects (which forks git) or qits-ci (which drives docker),
# this service shells out to nothing at all — the binary and libc are the whole runtime. If that ever
# stops being true, the missing executable will surface as an IOException at the first request rather
# than at build time, so add the package in the same commit as the code that forks it.

# `quarkus.package.output-name` names the binary `qits-events` (no -runner suffix), so the process is
# identifiable in `ps`/`docker top`. It is built by `service`, the one module of the two that is an
# application; `events` is a library jar with nothing to native-compile.
COPY --from=build --chown=1001:root /src/service/target/qits-events /work/qits-events
RUN chmod +x /work/qits-events

# NO MOUNT POINT FOR DATA, and its absence is the change the move to PostgreSQL bought. There used
# to be an owned `/data/events` here for the H2 file, because docker seeds a fresh named volume from
# whatever the image has at the mount path and a missing directory made the boot die on an
# AccessDeniedException instead of on the config error. The store is another container's now: this
# service writes nothing to disk that outlives it, so there is no volume to own and none to back up.

EXPOSE 8080
USER 1001

# Everything this service serves hangs off /events — /events/, /events/api/*, /events/q/* — because
# qits-gateway routes verbatim by prefix. The paths are in application.properties, not here; see
# README.md.
ENTRYPOINT ["/work/qits-events", "-Dquarkus.http.host=0.0.0.0"]
