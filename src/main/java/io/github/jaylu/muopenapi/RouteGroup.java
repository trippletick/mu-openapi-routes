package io.github.jaylu.muopenapi;

import io.muserver.ContextHandlerBuilder;
import io.muserver.Method;
import io.muserver.MuHandler;
import io.muserver.RouteHandler;

import java.util.function.Consumer;

/**
 * Documented routes that share a path prefix which the route handlers do not see, for example routes added to a
 * {@link ContextHandlerBuilder}. Create one with {@link ApiDocs#group(String)}, and use {@link #context()} to mount
 * its routes so the documented prefix and the served context come from the same path:
 * <pre><code>
 * RouteGroup v1 = api.group("v1");
 * server.addHandler(v1.context()
 *     .addHandler(v1.route(GET, "/status", op -&gt; ..., handler)));
 * </code></pre>
 */
public final class RouteGroup {
    private final ApiDocs api;
    private final String prefix;
    private final String segment;

    RouteGroup(ApiDocs api, String prefix, String segment) {
        this.api = api;
        this.prefix = prefix;
        this.segment = segment;
    }

    /**
     * Creates and documents a route in this group. See {@link ApiDocs#route(Method, String, Consumer, RouteHandler)}.
     *
     * @param method      The HTTP method
     * @param uriTemplate The URI template, relative to the group prefix
     * @param doc         A callback that documents the operation
     * @param handler     The handler to call when the route matches
     * @return A handler to add to the context for this group
     */
    public MuHandler route(Method method, String uriTemplate, Consumer<OperationDoc> doc, RouteHandler handler) {
        return api.register(prefix, method, uriTemplate, doc, handler);
    }

    /**
     * @param prefix A further prefix, e.g. <code>/admin</code>
     * @return A nested group
     */
    public RouteGroup group(String prefix) {
        return new RouteGroup(api, PathTemplate.join(this.prefix, prefix), PathTemplate.join("", prefix));
    }

    /**
     * Creates a mu-server context for this group, so the routes are served under the same path they are
     * documented under.
     * <p>The context uses the path this group adds to its parent group. For a nested group, add its context inside
     * the parent group's context:</p>
     * <pre><code>
     * RouteGroup api = docs.group("api");
     * RouteGroup v1 = api.group("v1");
     * server.addHandler(api.context()
     *     .addHandler(v1.context()
     *         .addHandler(v1.route(GET, "/status", op -&gt; ..., handler))));   // served at /api/v1/status
     * </code></pre>
     *
     * @return A new context handler builder to add this group's routes to
     */
    public ContextHandlerBuilder context() {
        return ContextHandlerBuilder.context(segment);
    }

    /**
     * @return The path prefix of this group
     */
    public String prefix() {
        return PathTemplate.join(prefix, "");
    }
}
