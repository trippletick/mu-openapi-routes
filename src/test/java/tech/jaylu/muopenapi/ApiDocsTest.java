package tech.jaylu.muopenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.muserver.MuServer;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts {@link SampleApi} and checks the served OpenAPI document, and that routing still works.
 */
class ApiDocsTest {

    static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient client = HttpClient.newHttpClient();
    private static MuServer server;
    private static String json;
    private static JsonNode doc;

    @BeforeAll
    static void start() throws Exception {
        server = new SampleApi().start();
        HttpResponse<String> resp = get("/openapi.json");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        json = resp.body();
        doc = mapper.readTree(json);
        // Written so the generated document can be inspected, e.g. in https://editor.swagger.io
        Path out = Path.of("target", "sample-openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void theDocumentIsValidOpenApi() {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(json);
        assertNotNull(result.getOpenAPI());
        assertEquals(List.of(), result.getMessages());
        assertEquals("3.0.1", doc.get("openapi").asText());
        assertEquals("Users API", doc.at("/info/title").asText());
        assertEquals("1.0", doc.at("/info/version").asText());
    }

    @Test
    void pathsUseOpenApiTemplatesWithoutRegexes() {
        assertEquals(sorted("/users", "/users/{id}", "/users/{id}/avatar", "/echo", "/v1/status"), keys(doc.get("paths")));
        assertEquals(sorted("get", "put", "patch", "delete"), keys(doc.get("paths").get("/users/{id}")));
        assertEquals(sorted("get", "post"), keys(doc.get("paths").get("/users")));
    }

    @Test
    void getWithQueryAndHeaderParameters() {
        JsonNode op = op("/users", "get");
        assertEquals("List users", op.get("summary").asText());
        assertEquals("GET_users", op.get("operationId").asText());
        assertEquals(List.of("users"), texts(op.get("tags")));

        JsonNode limit = param(op, "query", "limit");
        assertFalse(limit.path("required").asBoolean());
        assertEquals("Maximum number of users to return", limit.get("description").asText());
        assertEquals("integer", limit.at("/schema/type").asText());
        assertEquals("int32", limit.at("/schema/format").asText());
        assertEquals(20, limit.at("/schema/default").asInt());

        JsonNode role = param(op, "query", "role").get("schema");
        assertEquals("string", role.get("type").asText());
        assertEquals(List.of("ADMIN", "MEMBER"), texts(role.get("enum")));

        JsonNode tag = param(op, "query", "tag").get("schema");
        assertEquals("array", tag.get("type").asText());
        assertEquals("string", tag.at("/items/type").asText());

        JsonNode header = param(op, "header", "X-Request-Id");
        assertEquals("string", header.at("/schema/type").asText());
        assertFalse(op.has("requestBody"));
    }

    @Test
    void genericResponseBodiesAreResolved() {
        JsonNode page = responseSchema(op("/users", "get"), "200", "application/json");
        assertEquals("object", page.get("type").asText());
        assertEquals(sorted("items", "total", "next"), keys(page.get("properties")));
        JsonNode items = page.at("/properties/items");
        assertEquals("array", items.get("type").asText());
        assertEquals("User", items.at("/items/title").asText());
        assertUserSchema(items.get("items"));
    }

    @Test
    void getWithPathParameter() {
        JsonNode op = op("/users/{id}", "get");
        JsonNode id = param(op, "path", "id");
        assertTrue(id.get("required").asBoolean());
        assertEquals("The user ID", id.get("description").asText());
        assertEquals("integer", id.at("/schema/type").asText());
        assertEquals("int64", id.at("/schema/format").asText());
        // The regex only applies to strings, so it is not added to an integer schema
        assertFalse(id.get("schema").has("pattern"));

        assertUserSchema(responseSchema(op, "200", "application/json"));
        JsonNode notFound = op.at("/responses/404");
        assertEquals("No user has this ID", notFound.get("description").asText());
        JsonNode problem = notFound.get("content").get(SampleApi.PROBLEM_JSON).get("schema");
        assertEquals(sorted("title", "status", "detail"), keys(problem.get("properties")));
    }

    @Test
    void undocumentedPathParametersAreAddedWithTheirRegex() {
        JsonNode id = param(op("/users/{id}/avatar", "put"), "path", "id");
        assertTrue(id.get("required").asBoolean());
        assertEquals("string", id.at("/schema/type").asText());
        assertEquals("[0-9]+", id.at("/schema/pattern").asText());
    }

    @Test
    void postWithJsonBodyExampleAndResponseHeader() {
        JsonNode op = op("/users", "post");
        JsonNode body = op.get("requestBody");
        assertTrue(body.get("required").asBoolean());
        assertEquals("The user to create", body.get("description").asText());
        JsonNode media = body.get("content").get("application/json");
        JsonNode schema = media.get("schema");
        assertEquals("NewUser", schema.get("title").asText());
        assertEquals(sorted("name", "email", "role", "tags", "address"), keys(schema.get("properties")));
        assertEquals(sorted("street", "city", "postcode"), keys(schema.at("/properties/address/properties")));
        assertEquals("Ada", media.at("/example/name").asText());

        JsonNode created = op.at("/responses/201");
        assertEquals("The created user", created.get("description").asText());
        assertEquals("string", created.at("/headers/Location/schema/type").asText());
        assertUserSchema(created.get("content").get("application/json").get("schema"));
        assertTrue(op.get("responses").has("400"));
    }

    @Test
    void putWithJsonBody() {
        JsonNode op = op("/users/{id}", "put");
        assertEquals("PUT_users__id_", op.get("operationId").asText());
        assertEquals("NewUser", op.get("requestBody").get("content").get("application/json").at("/schema/title").asText());
        assertUserSchema(responseSchema(op, "200", "application/json"));
        assertEquals("path", op.at("/parameters/0/in").asText());
    }

    @Test
    void patchWithMergePatchBodyFromAPlainClass() {
        JsonNode content = op("/users/{id}", "patch").at("/requestBody/content");
        assertEquals(sorted("application/merge-patch+json"), keys(content));
        JsonNode schema = content.get("application/merge-patch+json").get("schema");
        assertEquals("UserPatch", schema.get("title").asText());
        assertEquals(sorted("name", "role"), keys(schema.get("properties")));
        assertEquals("string", schema.at("/properties/name/type").asText());
    }

    @Test
    void deleteWithNoContentResponse() {
        JsonNode op = op("/users/{id}", "delete");
        JsonNode noContent = op.at("/responses/204");
        assertEquals("The user was deleted", noContent.get("description").asText());
        assertFalse(noContent.has("content"));
        assertFalse(op.has("requestBody"));
    }

    @Test
    void binaryBodiesWithSeveralMediaTypes() {
        JsonNode content = op("/users/{id}/avatar", "put").at("/requestBody/content");
        assertEquals(sorted("image/png", "image/jpeg"), keys(content));
        JsonNode png = content.get("image/png").get("schema");
        assertEquals("string", png.get("type").asText());
        assertEquals("binary", png.get("format").asText());
        assertEquals("A PNG image", op("/users/{id}/avatar", "put").at("/requestBody/description").asText());
    }

    @Test
    void textBodies() {
        JsonNode op = op("/echo", "post");
        assertEquals("string", op.get("requestBody").get("content").get("text/plain").at("/schema/type").asText());
        assertEquals("string", responseSchema(op, "200", "text/plain").get("type").asText());
        assertFalse(op.has("tags"));
    }

    @Test
    void groupPrefixesAreDocumentedAndDeprecationIsShown() {
        JsonNode op = op("/v1/status", "get");
        assertTrue(op.get("deprecated").asBoolean());
        assertEquals("GET_v1_status", op.get("operationId").asText());
    }

    @Test
    void tagsAreListedWithDescriptions() {
        JsonNode tags = doc.get("tags");
        assertEquals(1, tags.size());
        assertEquals("users", tags.at("/0/name").asText());
        assertEquals("Manage users", tags.at("/0/description").asText());
    }

    @Test
    void noServersAreListedWhenThereIsNoContextPath() {
        assertFalse(doc.has("servers"));
    }

    @Test
    void routesStillWork() throws Exception {
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/users"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ada\",\"email\":\"ada@example.org\"}")));
        assertEquals(201, created.statusCode());
        String location = created.headers().firstValue("location").orElseThrow();
        long id = mapper.readTree(created.body()).get("id").asLong();
        assertEquals("/users/" + id, location);

        assertEquals("Ada", mapper.readTree(get(location).body()).get("name").asText());

        HttpResponse<String> patched = send(HttpRequest.newBuilder(uri(location))
            .header("content-type", "application/merge-patch+json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"ADMIN\"}")));
        assertEquals(200, patched.statusCode());
        assertEquals("ADMIN", mapper.readTree(patched.body()).get("role").asText());

        assertEquals(1, mapper.readTree(get("/users?limit=5").body()).get("items").size());
        assertEquals(404, get("/users/abc").statusCode(), "The regex in the template is still used for matching");
        assertEquals(204, send(HttpRequest.newBuilder(uri(location)).DELETE()).statusCode());
        assertEquals(404, get(location).statusCode());
        assertEquals("OK", get("/v1/status").body());
        assertEquals(404, get("/status").statusCode());
    }

    private static void assertUserSchema(JsonNode user) {
        assertEquals("object", user.get("type").asText());
        JsonNode props = user.get("properties");
        assertEquals(sorted("id", "name", "email", "role", "tags", "address", "createdAt"), keys(props));
        assertEquals("int64", props.at("/id/format").asText());
        assertEquals("date-time", props.at("/createdAt/format").asText());
        assertEquals(List.of("ADMIN", "MEMBER"), texts(props.at("/role/enum")));
    }

    private static JsonNode op(String path, String method) {
        JsonNode op = doc.get("paths").get(path).get(method);
        assertNotNull(op, method + " " + path);
        return op;
    }

    private static JsonNode param(JsonNode op, String in, String name) {
        for (JsonNode p : op.get("parameters")) {
            if (p.get("in").asText().equals(in) && p.get("name").asText().equals(name)) {
                return p;
            }
        }
        throw new AssertionError("No " + in + " parameter " + name + " in " + op.get("parameters"));
    }

    private static JsonNode responseSchema(JsonNode op, String status, String mediaType) {
        return op.get("responses").get(status).get("content").get(mediaType).get("schema");
    }

    /**
     * The sorted field names of a JSON object. mu-server 2.3.2 copies maps into a HashMap when building
     * the OpenAPI model, so the declared order of paths, properties and media types is not kept.
     */
    static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(null);
        return keys;
    }

    static List<String> sorted(String... values) {
        List<String> list = new ArrayList<>(List.of(values));
        list.sort(null);
        return list;
    }

    static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static URI uri(String path) {
        return server.uri().resolve(path);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
