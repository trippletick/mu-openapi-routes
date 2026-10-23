package tech.jaylu.muopenapi;

import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuHandler;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.RouteHandler;
import io.muserver.Routes;
import io.muserver.openapi.OpenAPIObject;
import io.muserver.openapi.OpenAPIObjectBuilder;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.PathItemObject;
import io.muserver.openapi.SchemaObject;
import io.muserver.openapi.TagObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.OpenAPIObjectBuilder.openAPIObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static io.muserver.openapi.TagObjectBuilder.tagObject;

/**
 * Registers functional-style routes together with their OpenAPI documentation, and serves the resulting
 * OpenAPI document.
 * <pre><code>
 * ApiDocs api = ApiDocs.apiDocs("Users API", "1.0");
 * MuServer server = MuServerBuilder.httpServer()
 *     .addHandler(api.route(Method.GET, "/users/{id : [0-9]+}",
 *         op -&gt; op.summary("Get a user")
 *             .pathParam("id", long.class, "The user ID")
 *             .response(200, User.class, "The user")
 *             .response(404, "No such user"),
 *         (request, response, pathParams) -&gt; { ... }))
 *     .addHandler(api.openApiJson("/openapi.json"))
 *     .start();
 * </code></pre>
 * <p>Documentation is checked when a route is registered, so mistakes such as documenting a path parameter that is
 * not in the URI template fail at startup.</p>
 */
public final class ApiDocs {

    private final OpenAPIObject base;
    private final Map<Class<?>, SchemaObject> customSchemas = new ConcurrentHashMap<>();
    private final SchemaProvider schemas = this::schemaFor;
    private final List<DocumentedRoute> routes = new ArrayList<>();
    private final Map<String, TagObject> tags = new LinkedHashMap<>();
    private final RouteGroup root = new RouteGroup(this, "", "");
    private volatile SchemaProvider schemaProvider = new ReflectionSchemaProvider(customSchemas);
    private volatile boolean strict;

    private ApiDocs(OpenAPIObject base) {
        this.base = base;
    }

    /**
     * Creates documentation for an API with the given title and version
     *
     * @param title   The API title
     * @param version The API version
     * @return A new ApiDocs
     */
    public static ApiDocs apiDocs(String title, String version) {
        return apiDocs(openAPIObject().withInfo(infoObject().withTitle(title).withVersion(version).build()));
    }

    /**
     * Creates documentation from an existing OpenAPI object. The info, servers, components, security, tags and
     * external docs are kept; the paths are replaced by the documented routes.
     * <p>When no servers are given, the served document uses the request's context path as the server URL.</p>
     *
     * @param base An OpenAPI object builder with at least the info set
     * @return A new ApiDocs
     */
    public static ApiDocs apiDocs(OpenAPIObjectBuilder base) {
        Objects.requireNonNull(base, "base");
        return new ApiDocs(base.withPaths(pathsObject().build()).build());
    }

    /**
     * In strict mode, registering a route with no summary or no documented responses fails.
     * Set this before registering routes.
     *
     * @param strict True to turn on strict mode
     * @return This object
     */
    public ApiDocs withStrictMode(boolean strict) {
        this.strict = strict;
        return this;
    }

    /**
     * Replaces the {@link ReflectionSchemaProvider} used to describe body and parameter types.
     * Schemas added with {@link #addSchema(Class, SchemaObject)} still take precedence at the top level.
     *
     * @param schemaProvider The provider to use
     * @return This object
     */
    public ApiDocs withSchemaProvider(SchemaProvider schemaProvider) {
        this.schemaProvider = Objects.requireNonNull(schemaProvider, "schemaProvider");
        return this;
    }

    /**
     * Uses the given schema whenever the class is documented, instead of generating one.
     *
     * @param type   The class
     * @param schema The schema to use for it
     * @return This object
     */
    public ApiDocs addSchema(Class<?> type, SchemaObject schema) {
        customSchemas.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(schema, "schema"));
        return this;
    }

    /**
     * Adds a description for a tag used by operations. Tags are listed in the order they are added, followed by
     * any other tags used by operations.
     *
     * @param name        The tag name
     * @param description The tag description
     * @return This object
     */
    public ApiDocs addTag(String name, String description) {
        synchronized (tags) {
            tags.put(name, tagObject().withName(name).withDescription(description).build());
        }
        return this;
    }

    /**
     * Creates a route handler and documents it. The returned handler behaves the same as
     * {@link Routes#route(Method, String, RouteHandler)}.
     *
     * @param method      The HTTP method. Unlike {@link Routes#route(Method, String, RouteHandler)}, null is not
     *                    allowed, because an operation is documented per method.
     * @param uriTemplate The URI template, e.g. <code>/users/{id : [0-9]+}</code>
     * @param doc         A callback that documents the operation
     * @param handler     The handler to call when the route matches
     * @return A handler to add to a server, e.g. with {@link io.muserver.MuServerBuilder#addHandler(MuHandler)}
     * @throws IllegalArgumentException if the documentation does not match the route
     */
    public MuHandler route(Method method, String uriTemplate, Consumer<OperationDoc> doc, RouteHandler handler) {
        return root.route(method, uriTemplate, doc, handler);
    }

    /**
     * Creates a group for routes that are added under a path prefix the route handler does not see, such as the
     * context of a {@link io.muserver.ContextHandlerBuilder}. The prefix is added to the documented paths.
     * <pre><code>
     * RouteGroup v1 = api.group("/v1");
     * server.addHandler(v1.context().addHandler(v1.route(GET, "/users", ...)));
     * </code></pre>
     *
     * @param prefix The path prefix, e.g. <code>/v1</code>
     * @return A route group
     */
    public RouteGroup group(String prefix) {
        return root.group(prefix);
    }

    /**
     * Builds the OpenAPI document
     *
     * @param contextPath The context path the API is served under, used as the server URL when no servers were
     *                    given. Use an empty string for none.
     * @return The OpenAPI document
     */
    public OpenAPIObject document(String contextPath) {
        Map<String, Map<String, OperationObject>> operationsByPath = new LinkedHashMap<>();
        List<String> usedTags = new ArrayList<>();
        for (DocumentedRoute route : snapshot()) {
            OperationObject operation = route.doc.build(schemas);
            operationsByPath.computeIfAbsent(route.path.openApiPath, k -> new LinkedHashMap<>())
                .put(route.method.name().toLowerCase(), operation);
            for (String tag : route.doc.tagsValue()) {
                if (!usedTags.contains(tag)) {
                    usedTags.add(tag);
                }
            }
        }
        Map<String, PathItemObject> pathItems = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, OperationObject>> entry : operationsByPath.entrySet()) {
            pathItems.put(entry.getKey(), pathItemObject().withOperations(entry.getValue()).build());
        }

        Map<String, TagObject> allTags = new LinkedHashMap<>();
        if (base.tags() != null) {
            for (TagObject tag : base.tags()) {
                allTags.put(tag.name(), tag);
            }
        }
        synchronized (tags) {
            allTags.putAll(tags);
        }
        for (String tag : usedTags) {
            if (!allTags.containsKey(tag)) {
                allTags.put(tag, tagObject().withName(tag).build());
            }
        }

        String context = PathTemplate.trimSlashes(contextPath);
        return openAPIObject()
            .withInfo(base.info())
            .withExternalDocs(base.externalDocs())
            .withSecurity(base.security())
            .withComponents(base.components())
            .withServers(base.servers() != null ? base.servers()
                : context.isEmpty() ? null : Collections.singletonList(serverObject().withUrl("/" + context).build()))
            .withPaths(pathsObject().withPathItemObjects(pathItems).build())
            .withTags(allTags.isEmpty() ? null : new ArrayList<>(allTags.values()))
            .build();
    }

    /**
     * Builds the OpenAPI document as JSON, e.g. to write it to a file during a build or to compare it in a test
     *
     * @return The OpenAPI document as JSON
     */
    public String toJson() {
        StringWriter writer = new StringWriter();
        try {
            document("").writeJson(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.toString();
    }

    /**
     * Creates a handler that serves the OpenAPI document as JSON for <code>GET</code> requests to the given path.
     * <p>The path is relative to the context the handler is added to.</p>
     *
     * @param path The path to serve the JSON at, e.g. <code>/openapi.json</code>
     * @return A handler to add to a server
     */
    public MuHandler openApiJson(String path) {
        String target = PathTemplate.trimSlashes(Objects.requireNonNull(path, "path"));
        return new MuHandler() {
            @Override
            public boolean handle(MuRequest request, MuResponse response) throws Exception {
                if (request.method() != Method.GET || !PathTemplate.trimSlashes(request.relativePath()).equals(target)) {
                    return false;
                }
                // Written to a string first so that a failure results in a 500 rather than a truncated response
                StringWriter json = new StringWriter();
                document(request.contextPath()).writeJson(json);
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write(json.toString());
                return true;
            }

            @Override
            public String toString() {
                return "OpenApiJsonHandler{path='/" + target + "'}";
            }
        };
    }

    MuHandler register(String prefix, Method method, String uriTemplate, Consumer<OperationDoc> docCallback, RouteHandler handler) {
        if (method == null) {
            throw new IllegalArgumentException("method cannot be null for a documented route");
        }
        if (method == Method.CONNECT) {
            throw new IllegalArgumentException("OpenAPI cannot document CONNECT operations");
        }
        Objects.requireNonNull(docCallback, "doc");
        Objects.requireNonNull(handler, "handler");
        PathTemplate path = PathTemplate.parse(prefix, uriTemplate);
        OperationDoc doc = new OperationDoc(method, path);
        docCallback.accept(doc);

        String where = method + " " + path.openApiPath;
        if (strict) {
            if (doc.summaryValue() == null || doc.summaryValue().trim().isEmpty()) {
                throw new IllegalArgumentException("Strict mode: " + where + " has no summary");
            }
            if (!doc.hasResponses()) {
                throw new IllegalArgumentException("Strict mode: " + where + " has no documented responses");
            }
        }
        doc.build(schemas); // fail fast on invalid documentation

        synchronized (routes) {
            for (DocumentedRoute existing : routes) {
                if (existing.method == method && existing.path.shape.equals(path.shape)) {
                    throw new IllegalArgumentException(where + " is already documented as "
                        + existing.method + " " + existing.path.openApiPath);
                }
                if (existing.doc.operationIdValue().equals(doc.operationIdValue())) {
                    throw new IllegalArgumentException("The operation ID '" + doc.operationIdValue() + "' of " + where
                        + " is already used by " + existing.method + " " + existing.path.openApiPath);
                }
            }
            routes.add(new DocumentedRoute(method, path, doc));
        }
        return Routes.route(method, uriTemplate, handler);
    }

    private List<DocumentedRoute> snapshot() {
        synchronized (routes) {
            return new ArrayList<>(routes);
        }
    }

    private SchemaObject schemaFor(Type type) {
        Type unwrapped = TypeRef.unwrap(type);
        if (unwrapped instanceof Class) {
            SchemaObject custom = customSchemas.get(unwrapped);
            if (custom != null) {
                return custom;
            }
        }
        return schemaProvider.schemaFor(unwrapped);
    }

    private static final class DocumentedRoute {
        final Method method;
        final PathTemplate path;
        final OperationDoc doc;

        DocumentedRoute(Method method, PathTemplate path, OperationDoc doc) {
            this.method = method;
            this.path = path;
            this.doc = doc;
        }
    }
}
