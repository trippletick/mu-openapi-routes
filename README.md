# mu-openapi-routes

OpenAPI documentation for [Mu Server](https://muserver.io) functional-style route handlers.

Each route is registered and documented in one call, so the documentation lives next to the handler. The
documentation is checked against the route when it is registered, and the resulting `openapi.json` is served by
an ordinary `MuHandler`.

```java
ApiDocs api = ApiDocs.apiDocs("Users API", "1.0")
    .withStrictMode(true)                 // every route needs a summary and responses
    .addTag("users", "Manage users");

MuServer server = MuServerBuilder.httpServer()
    .addHandler(api.route(Method.GET, "/users",
        op -> op.summary("List users").tag("users")
            .queryParam("limit", int.class, "Maximum number of users", 20)
            .queryParam("tag", new TypeRef<List<String>>() {}, "Filter by tags")
            .response(200, new TypeRef<Page<User>>() {}, "A page of users"),
        (req, resp, params) -> { ... }))
    .addHandler(api.route(Method.GET, "/users/{id : [0-9]+}",
        op -> op.summary("Get a user").tag("users")
            .pathParam("id", long.class, "The user ID")
            .response(200, User.class, "The user")
            .response(404, "application/problem+json", Problem.class, "No such user"),
        (req, resp, params) -> { ... }))
    .addHandler(api.route(Method.POST, "/users",
        op -> op.summary("Create a user").tag("users")
            .requestBody(NewUser.class, "The user to create")
            .response(201, User.class, "The created user")
            .responseHeader(201, "Location", String.class, "The URL of the new user"),
        (req, resp, params) -> { ... }))
    .addHandler(api.openApiJson("/openapi.json"))
    .start();
```

See `src/test/java/io/github/jaylu/muopenapi/SampleApi.java` for a complete example covering GET, POST, PUT,
PATCH and DELETE, query, path and header parameters, and JSON, text and binary bodies. Running the tests writes
the generated document to `target/sample-openapi.json`.

## What is checked at registration

- Documenting a path parameter that is not in the URI template fails.
- Path parameters in the template that are not documented are added as strings. A template regex such as
  `{id : [0-9]+}` becomes the schema `pattern` for string parameters.
- The same method and path cannot be documented twice, even with different parameter names.
- Operation IDs must be unique. By default they are generated as `METHOD_path`, e.g. `GET_users__id_`.
- A parameter cannot be documented twice.
- In strict mode, every route needs a summary and at least one documented response.

## Routes under a context

Routes inside a mu-server context only see the path relative to the context, so document them with a group,
and mount the group's routes in `group.context()`:

```java
RouteGroup v1 = api.group("v1");
server.addHandler(v1.context()                    // a ContextHandlerBuilder for "v1"
    .addHandler(v1.route(GET, "/status", op -> ..., handler))   // documented and served as /v1/status
    .addHandler(GET, "/health", healthHandler));                // undocumented handlers can go here too
```

`group.context()` returns an ordinary `ContextHandlerBuilder`, so the prefix is written once and the documented
path can't drift from the served one. Each call returns a new builder.

Groups can be nested. A nested group's `context()` uses only the path it adds, so put it inside its parent's
context, the same way mu-server contexts nest:

```java
RouteGroup cp1 = api.group("context-path-1");
RouteGroup v1 = cp1.group("v1");                  // prefix /context-path-1/v1
server.addHandler(cp1.context()
    .addHandler(cp1.route(GET, "/users", op -> ..., listUsers))         // /context-path-1/users
    .addHandler(v1.context()
        .addHandler(v1.route(GET, "/session", op -> ..., getSession)))); // /context-path-1/v1/session
```

Group prefixes are relative to **where `openApiJson` is mounted**, usually the server root.

### Several context paths in one document

To serve the same routes under several contexts, for example `/context-path-1/users`, `/context-path-1/session`,
`/context-path-2/users` and `/context-path-2/session`, write the module as a method that takes the context path
and mounts its routes in `group.context()`:

```java
static ContextHandlerBuilder usersAndSession(ApiDocs api, String contextPath) {
    RouteGroup group = api.group(contextPath);
    return group.context()
        .addHandler(group.route(GET, "/users", op -> op.summary("List users").tag("users"), listUsers))
        .addHandler(group.route(GET, "/users/{id : [0-9]+}", op -> ..., getUser))
        .addHandler(group.route(POST, "/session", op -> op.summary("Log in").tag("session"), login))
        .addHandler(group.route(DELETE, "/session", op -> ..., logout));
}

ApiDocs api = ApiDocs.apiDocs("My API", "1.0");
MuServer server = MuServerBuilder.httpServer()
    .addHandler(usersAndSession(api, "context-path-1"))
    .addHandler(usersAndSession(api, "context-path-2"))
    .addHandler(api.openApiJson("/openapi.json"))        // at the root: one document for both contexts
    .start();
```

The result is a single document with all six paths:

| | `context-path-1` | `context-path-2` |
|---|---|---|
| Paths | `/context-path-1/users`, `/context-path-1/users/{id}`, `/context-path-1/session` | the same under `/context-path-2` |
| Generated operation IDs | `GET_context-path-1_users`, … | `GET_context-path-2_users`, … |
| Tags | `users` and `session` are listed once and shared by both contexts | |

Things to know:

- **Explicit operation IDs must be unique across contexts.** If the module sets `operationId("listUsers")`,
  registering it for the second context fails with `The operation ID 'listUsers' of GET /context-path-2/users is
  already used by GET /context-path-1/users`. Either leave operation IDs unset, so they are generated from the
  full path, or build them from the context, e.g. `operationId(contextPath + "-listUsers")`.
- **Mount `openApiJson` at the level the group prefixes are relative to**, not inside `group.context()`. See
  [Pitfall: the document served inside a group's context](#pitfall-the-document-served-inside-a-groups-context).
- **One document per context** is also possible. Use a separate `ApiDocs` per context, document the routes at
  its root (no group), and add `openApiJson` inside a plain `context("context-path-N")`. Each document then
  lists `/context-path-N` as its server and paths such as `/users`.

`MultipleContextsTest` covers all of these cases against a running server.

### Pitfall: the document served inside a group's context

`group.context()` keeps each route in step with its documentation, but it can't control where `openApiJson` is
mounted. Group prefixes are relative to the place the document is served from, so serving the document from
inside the group's own context counts the context twice.

**Bad:** the document is added to the group's context.

```java
RouteGroup cp1 = api.group("context-path-1");
server.addHandler(cp1.context()
    .addHandler(cp1.route(GET, "/users", op -> ..., listUsers))
    .addHandler(api.openApiJson("/openapi.json")));   // wrong: inside context-path-1
```

`GET /context-path-1/openapi.json` returns a document that adds the context as the server URL **and** keeps it in
every path:

```json
{
  "servers": [ { "url": "/context-path-1" } ],
  "paths":   { "/context-path-1/users": { "get": { ... } } }
}
```

A client such as Swagger UI joins the two and calls `/context-path-1/context-path-1/users`, which returns 404.
Nothing fails at startup, and the paths in the document look right at a glance, so this is easy to miss.

**Good:** the document is added at the root, which is where the group prefixes are relative to.

```java
RouteGroup cp1 = api.group("context-path-1");
server.addHandler(cp1.context()
    .addHandler(cp1.route(GET, "/users", op -> ..., listUsers)))
  .addHandler(api.openApiJson("/openapi.json"));      // right: at the root
```

`GET /openapi.json` now lists `/context-path-1/users` with no server URL, so clients call the real path.

If you want the document served under the context instead, don't use a group: document the routes at the root
of their own `ApiDocs` and add both to a plain `context("context-path-1")`, as described in "One document per
context" above. Then the server URL is `/context-path-1` and the path is `/users`.

Both examples run against a real server in `MultipleContextsTest`:
`aGroupedDocumentServedInsideTheContextRepeatsThePrefix` checks that the URL a client builds from the bad document
returns 404, and `aGroupedDocumentServedAtTheRootGivesWorkingUrls` checks that the good one works.

## Schemas

Body and parameter types are described by `ReflectionSchemaProvider`: simple types, enums, arrays, collections,
maps, `Optional`, records, and classes with public fields or getters. Generic types are resolved, e.g. `Page<User>`.
Use `api.addSchema(Money.class, schema)` to override a class, or `withSchemaProvider(...)` to plug in another
generator.

## Known limitations (mu-server 2.3.2)

- **Order is not kept.** mu-server copies the model's maps into a `HashMap`, so paths, properties and
  media types are not written in the order they were declared. The fix in mu-server is to use a `LinkedHashMap`
  in `OpenApiUtils.immutable`.
- **No `$ref`.** mu-server's `SchemaObject` cannot express `$ref`, so DTO schemas are inlined and recursive
  types stop at the point of recursion.
- **Enum values must be Java enums.** `SchemaObject` writes `enum` values via `Enum.name()`, so a list of plain
  strings cannot be used.
- **Examples need maps or lists.** Examples are written by mu-server's `Jsonizer`, which supports strings,
  numbers, booleans, lists and maps; other objects are written using `toString()`.
- **JSON only.** There is no HTML page, because mu-server's `HtmlDocumentor` is package-private. Point Swagger UI
  or Redoc at `/openapi.json`, or merge into `RestHandlerBuilder` once mu-server stops overwriting user-supplied
  paths.

## Build

Requires a JDK 17+ to build (tests use records); the library itself targets Java 8, the same as mu-server 2.x.

```
mvn test
```

## Using it in your project

The library is on Maven Central:

```xml
<dependency>
    <groupId>tech.jaylu</groupId>
    <artifactId>mu-openapi-routes</artifactId>
    <version>0.1.0</version>
</dependency>
```

Release notes are on the [GitHub releases page](https://github.com/trippletick/mu-openapi-routes/releases). See
[RELEASE.md](RELEASE.md) for how releases are made.

It depends on `io.muserver:mu-server` 2.3.2 or later in the 2.x line.

## License

[MIT](LICENSE)
