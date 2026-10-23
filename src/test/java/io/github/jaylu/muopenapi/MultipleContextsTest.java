package io.github.jaylu.muopenapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.muserver.ContextHandlerBuilder;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.RouteHandler;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static io.github.jaylu.muopenapi.ApiDocsTest.keys;
import static io.github.jaylu.muopenapi.ApiDocsTest.mapper;
import static io.github.jaylu.muopenapi.ApiDocsTest.sorted;
import static io.github.jaylu.muopenapi.ApiDocsTest.texts;
import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.Method.DELETE;
import static io.muserver.Method.GET;
import static io.muserver.Method.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The same users and session routes mounted under two contexts, documented in one OpenAPI document:
 * <pre>
 * /context-path-1/users, /context-path-1/users/{id}, /context-path-1/session
 * /context-path-2/users, /context-path-2/users/{id}, /context-path-2/session
 * </pre>
 */
class MultipleContextsTest {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static MuServer server;
    private static JsonNode doc;
    private static String json;

    /** Writes which context and route handled the request, so tests can check the routing */
    private static RouteHandler echo(String name) {
        return (req, resp, params) -> resp.write(req.contextPath() + " " + name + " " + params);
    }

    /**
     * A reusable module. It documents its routes in a group and mounts them in that group's context, so the
     * documented prefix and the served context come from the same string.
     */
    static ContextHandlerBuilder usersAndSession(ApiDocs api, String contextPath) {
        RouteGroup group = api.group(contextPath);
        return group.context()
            .addHandler(group.route(GET, "/users",
                op -> op.summary("List users").tag("users").response(200, "The users"),
                echo("list-users")))
            .addHandler(group.route(GET, "/users/{id : [0-9]+}",
                op -> op.summary("Get a user").tag("users")
                    .pathParam("id", long.class, "The user ID")
                    .response(200, "The user").response(404, "No such user"),
                echo("get-user")))
            .addHandler(group.route(POST, "/session",
                op -> op.summary("Log in").tag("session").response(201, "Logged in"),
                echo("login")))
            .addHandler(group.route(DELETE, "/session",
                op -> op.summary("Log out").tag("session").response(204, "Logged out"),
                echo("logout")));
    }

    @BeforeAll
    static void start() throws Exception {
        ApiDocs api = ApiDocs.apiDocs("Multi-context API", "1.0")
            .addTag("users", "Manage users")
            .addTag("session", "Log in and out");
        server = MuServerBuilder.httpServer()
            .addHandler(usersAndSession(api, "context-path-1"))
            .addHandler(usersAndSession(api, "context-path-2"))
            .addHandler(api.openApiJson("/openapi.json")) // at the root, which is what group prefixes are relative to
            .start();
        HttpResponse<String> resp = get("/openapi.json");
        assertEquals(200, resp.statusCode());
        json = resp.body();
        doc = mapper.readTree(json);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void oneDocumentListsTheRoutesOfBothContexts() {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(json);
        assertNotNull(result.getOpenAPI());
        assertEquals(List.of(), result.getMessages());
        assertEquals(sorted(
            "/context-path-1/users", "/context-path-1/users/{id}", "/context-path-1/session",
            "/context-path-2/users", "/context-path-2/users/{id}", "/context-path-2/session"), keys(doc.get("paths")));
        assertEquals(sorted("post", "delete"), keys(doc.at("/paths/~1context-path-2~1session")));
        assertFalse(doc.has("servers"), "Served from the root, so the paths carry the context and no server is needed");
    }

    @Test
    void generatedOperationIdsIncludeTheContextSoTheyStayUnique() {
        List<String> ids = new ArrayList<>(doc.findValuesAsText("operationId"));
        ids.sort(null);
        assertEquals(sorted(
            "GET_context-path-1_users", "GET_context-path-1_users__id_", "POST_context-path-1_session",
            "DELETE_context-path-1_session",
            "GET_context-path-2_users", "GET_context-path-2_users__id_", "POST_context-path-2_session",
            "DELETE_context-path-2_session"), ids);
    }

    @Test
    void pathParametersAreDocumentedInEachContext() {
        for (String context : List.of("context-path-1", "context-path-2")) {
            JsonNode id = doc.at("/paths/~1" + context + "~1users~1{id}/get/parameters/0");
            assertEquals("id", id.get("name").asText());
            assertEquals("path", id.get("in").asText());
            assertEquals("integer", id.at("/schema/type").asText());
        }
    }

    @Test
    void tagsSharedByBothContextsAreListedOnce() {
        assertEquals(List.of("users", "session"), doc.get("tags").findValuesAsText("name"));
        assertEquals(List.of("users"), texts(doc.at("/paths/~1context-path-1~1users/get/tags")));
        assertEquals(List.of("users"), texts(doc.at("/paths/~1context-path-2~1users/get/tags")));
    }

    @Test
    void requestsReachTheRouteInTheRightContext() throws Exception {
        assertEquals("/context-path-1 list-users {}", get("/context-path-1/users").body());
        assertEquals("/context-path-2 get-user {id=42}", get("/context-path-2/users/42").body());
        assertEquals("/context-path-2 login {}", send(HttpRequest.newBuilder(server.uri().resolve("/context-path-2/session"))
            .POST(HttpRequest.BodyPublishers.noBody())).body());
        assertEquals(404, get("/users").statusCode());
        assertEquals(404, get("/context-path-1/users/abc").statusCode());
    }

    @Test
    void explicitOperationIdsMustBeUniqueAcrossContexts() {
        ApiDocs api = ApiDocs.apiDocs("T", "1");
        for (String context : List.of("context-path-1", "context-path-2")) {
            // Fine: the ID is derived from the context
            api.group(context).route(GET, "/session", op -> op.operationId(context + "-getSession"), echo("x"));
        }
        api.group("context-path-1").route(GET, "/users", op -> op.operationId("listUsers"), echo("x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> api.group("context-path-2").route(GET, "/users", op -> op.operationId("listUsers"), echo("x")));
        assertEquals("The operation ID 'listUsers' of GET /context-path-2/users is already used by "
            + "GET /context-path-1/users", e.getMessage());
    }

    @Test
    void nestedGroupsMountNestedContexts() throws Exception {
        ApiDocs api = ApiDocs.apiDocs("T", "1");
        RouteGroup outer = api.group("/context-path-1/");
        RouteGroup v1 = outer.group("v1");
        assertEquals("/context-path-1/v1", v1.prefix());
        MuServer nested = MuServerBuilder.httpServer()
            .addHandler(outer.context()
                .addHandler(outer.route(GET, "/users", op -> {}, echo("users")))
                .addHandler(v1.context()
                    .addHandler(v1.route(GET, "/session", op -> {}, echo("session")))))
            .addHandler(api.openApiJson("/openapi.json"))
            .start();
        try {
            JsonNode d = mapper.readTree(get(nested, "/openapi.json").body());
            assertEquals(sorted("/context-path-1/users", "/context-path-1/v1/session"), keys(d.get("paths")));
            assertEquals("/context-path-1 users {}", get(nested, "/context-path-1/users").body());
            assertEquals("/context-path-1/v1 session {}", get(nested, "/context-path-1/v1/session").body());
        } finally {
            nested.stop();
        }
    }

    @Test
    void aGroupContextIsAPlainContextHandlerBuilder() throws Exception {
        ApiDocs api = ApiDocs.apiDocs("T", "1");
        RouteGroup group = api.group("context-path-1");
        // Each call gives a new builder, and undocumented handlers can be added to it too
        assertNotSame(group.context(), group.context());
        MuServer s = MuServerBuilder.httpServer()
            .addHandler(group.context()
                .addHandler(group.route(GET, "/users", op -> {}, echo("users")))
                .addHandler(GET, "/health", (req, resp, params) -> resp.write("OK")))
            .addHandler(api.openApiJson("/openapi.json"))
            .start();
        try {
            assertEquals("OK", get(s, "/context-path-1/health").body());
            assertEquals(List.of("/context-path-1/users"), keys(mapper.readTree(get(s, "/openapi.json").body()).get("paths")));
        } finally {
            s.stop();
        }
    }

    @Test
    void eachContextCanServeItsOwnDocumentInstead() throws Exception {
        ApiDocs first = ApiDocs.apiDocs("Context 1", "1.0");
        ApiDocs second = ApiDocs.apiDocs("Context 2", "1.0");
        // Routes are added to the root of each ApiDocs (no group), because each document is served inside its context
        MuServer separate = MuServerBuilder.httpServer()
            .addHandler(context("context-path-1")
                .addHandler(first.route(GET, "/users", op -> {}, echo("list-users")))
                .addHandler(first.openApiJson("/openapi.json")))
            .addHandler(context("context-path-2")
                .addHandler(second.route(GET, "/session", op -> {}, echo("session")))
                .addHandler(second.openApiJson("/openapi.json")))
            .start();
        try {
            JsonNode doc1 = mapper.readTree(client.send(HttpRequest.newBuilder(separate.uri().resolve("/context-path-1/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("/context-path-1", doc1.at("/servers/0/url").asText());
            assertEquals(List.of("/users"), keys(doc1.get("paths")));

            JsonNode doc2 = mapper.readTree(client.send(HttpRequest.newBuilder(separate.uri().resolve("/context-path-2/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("/context-path-2", doc2.at("/servers/0/url").asText());
            assertEquals(List.of("/session"), keys(doc2.get("paths")));
        } finally {
            separate.stop();
        }
    }

    @Test
    void aGroupedDocumentServedInsideTheContextRepeatsThePrefix() throws Exception {
        // Bad example (see "Pitfall" in the README): group prefixes are relative to where openApiJson is mounted.
        // Mounting it inside the group's own context counts the context twice: once as the server URL and once in
        // every path.
        ApiDocs api = ApiDocs.apiDocs("T", "1");
        RouteGroup group = api.group("context-path-1");
        MuServer wrong = MuServerBuilder.httpServer()
            .addHandler(group.context()
                .addHandler(group.route(GET, "/users", op -> {}, echo("x")))
                .addHandler(api.openApiJson("/openapi.json")))
            .start();
        try {
            JsonNode d = mapper.readTree(get(wrong, "/context-path-1/openapi.json").body());
            String server = d.at("/servers/0/url").asText();
            String path = keys(d.get("paths")).get(0);
            assertEquals("/context-path-1", server);
            assertEquals("/context-path-1/users", path);
            // A client joins the server URL and the path, and calls a URL that does not exist
            assertEquals(404, get(wrong, server + path).statusCode());
            assertEquals(200, get(wrong, "/context-path-1/users").statusCode());
        } finally {
            wrong.stop();
        }
    }

    @Test
    void aGroupedDocumentServedAtTheRootGivesWorkingUrls() throws Exception {
        // The good version of the example above: only the openApiJson handler moves, to the root
        ApiDocs api = ApiDocs.apiDocs("T", "1");
        RouteGroup group = api.group("context-path-1");
        MuServer right = MuServerBuilder.httpServer()
            .addHandler(group.context()
                .addHandler(group.route(GET, "/users", op -> {}, echo("x"))))
            .addHandler(api.openApiJson("/openapi.json"))
            .start();
        try {
            JsonNode d = mapper.readTree(get(right, "/openapi.json").body());
            assertFalse(d.has("servers"));
            String path = keys(d.get("paths")).get(0);
            assertEquals("/context-path-1/users", path);
            assertEquals(200, get(right, path).statusCode());
        } finally {
            right.stop();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return get(server, path);
    }

    private static HttpResponse<String> get(MuServer target, String path) throws Exception {
        return send(HttpRequest.newBuilder(target.uri().resolve(path)));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
