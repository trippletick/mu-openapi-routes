package tech.jaylu.muopenapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.muserver.RouteHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static tech.jaylu.muopenapi.ApiDocsTest.keys;
import static tech.jaylu.muopenapi.ApiDocsTest.mapper;
import static tech.jaylu.muopenapi.ApiDocsTest.sorted;
import static io.muserver.Method.CONNECT;
import static io.muserver.Method.GET;
import static io.muserver.Method.POST;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.OpenAPIObjectBuilder.openAPIObject;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that documentation which does not match its route fails when the route is registered
 */
class ApiDocsValidationTest {

    private static final RouteHandler noop = (req, resp, params) -> {};
    private final ApiDocs api = ApiDocs.apiDocs("Test", "1");

    @Test
    void documentingAPathParamThatIsNotInTheTemplateFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            api.route(GET, "/users/{id}", op -> op.pathParam("userId", long.class, "ID"), noop));
        assertTrue(e.getMessage().contains("'userId'"), e.getMessage());
        assertTrue(e.getMessage().contains("[id]"), e.getMessage());
    }

    @Test
    void theSameRouteCannotBeDocumentedTwiceEvenWithDifferentParamNames() {
        api.route(GET, "/users/{id}", op -> {}, noop);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            api.route(GET, "/users/{userId : [0-9]+}", op -> {}, noop));
        assertTrue(e.getMessage().contains("already documented"), e.getMessage());
        // A different method on the same path is fine
        api.route(POST, "/users/{id}", op -> {}, noop);
    }

    @Test
    void operationIdsMustBeUnique() {
        api.route(GET, "/a", op -> op.operationId("same"), noop);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            api.route(GET, "/b", op -> op.operationId("same"), noop));
        assertTrue(e.getMessage().contains("'same'"), e.getMessage());
    }

    @Test
    void parametersCannotBeDocumentedTwice() {
        assertThrows(IllegalArgumentException.class, () -> api.route(GET, "/a",
            op -> op.queryParam("q", String.class, "one").requiredQueryParam("q", String.class, "two"), noop));
        assertThrows(IllegalArgumentException.class, () -> api.route(GET, "/b",
            op -> op.queryParam("q", String.class, "one")
                .parameter(parameterObject().withName("q").withIn("query").withSchema(schemaObject().withType("string").build()).build()),
            noop));
    }

    @Test
    void aResponseHeaderNeedsADocumentedResponse() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> api.route(POST, "/a",
            op -> op.responseHeader(201, "Location", String.class, "Where"), noop));
        assertTrue(e.getMessage().contains("response(201"), e.getMessage());
    }

    @Test
    void invalidStatusCodesFail() {
        assertThrows(IllegalArgumentException.class, () -> api.route(GET, "/a", op -> op.response(42, "Nope"), noop));
    }

    @Test
    void methodIsRequiredAndConnectIsNotSupported() {
        assertThrows(IllegalArgumentException.class, () -> api.route(null, "/a", op -> {}, noop));
        assertThrows(IllegalArgumentException.class, () -> api.route(CONNECT, "/a", op -> {}, noop));
    }

    @Test
    void invalidTemplatesFail() {
        assertThrows(IllegalArgumentException.class, () -> api.route(GET, "/a/{id", op -> {}, noop));
        assertThrows(IllegalArgumentException.class, () -> api.route(GET, "/a/{id}/{id}", op -> {}, noop));
    }

    @Test
    void strictModeRequiresASummaryAndResponses() {
        ApiDocs strict = ApiDocs.apiDocs("Test", "1").withStrictMode(true);
        IllegalArgumentException noSummary = assertThrows(IllegalArgumentException.class, () ->
            strict.route(GET, "/a", op -> op.response(200, "OK"), noop));
        assertTrue(noSummary.getMessage().contains("no summary"), noSummary.getMessage());
        IllegalArgumentException noResponses = assertThrows(IllegalArgumentException.class, () ->
            strict.route(GET, "/a", op -> op.summary("A"), noop));
        assertTrue(noResponses.getMessage().contains("no documented responses"), noResponses.getMessage());
        strict.route(GET, "/a", op -> op.summary("A").response(200, "OK"), noop);
    }

    @Test
    void withoutStrictModeAnUndocumentedRouteGetsADefaultResponse() throws Exception {
        api.route(GET, "/things/{name}", op -> {}, noop);
        JsonNode op = mapper.readTree(api.toJson()).at("/paths/~1things~1{name}/get");
        assertEquals("OK", op.at("/responses/200/description").asText());
        assertEquals("name", op.at("/parameters/0/name").asText());
        assertEquals("string", op.at("/parameters/0/schema/type").asText());
    }

    @Test
    void theContextPathIsUsedAsTheServerUnlessServersAreGiven() throws Exception {
        api.route(GET, "/a", op -> {}, noop);
        assertEquals("/api", api.document("/api/").servers().get(0).url());

        ApiDocs withServers = ApiDocs.apiDocs(openAPIObject()
            .withInfo(infoObject().withTitle("T").withVersion("1").build())
            .withServers(List.of(serverObject().withUrl("https://example.org").build())));
        assertEquals("https://example.org", withServers.document("/api").servers().get(0).url());
    }

    @Test
    void customizersCanChangeTheOperation() throws Exception {
        api.route(GET, "/a", op -> op.summary("Before").customize(b -> b.withSummary("After")), noop);
        assertEquals("After", mapper.readTree(api.toJson()).at("/paths/~1a/get/summary").asText());
    }

    @Test
    void nestedGroupsJoinPrefixes() throws Exception {
        RouteGroup admin = api.group("/api/").group("admin");
        assertEquals("/api/admin", admin.prefix());
        admin.route(GET, "/", op -> {}, noop);
        admin.route(GET, "users/{id}", op -> {}, noop);
        assertEquals(sorted("/api/admin", "/api/admin/users/{id}"), keys(mapper.readTree(api.toJson()).get("paths")));
    }
}
