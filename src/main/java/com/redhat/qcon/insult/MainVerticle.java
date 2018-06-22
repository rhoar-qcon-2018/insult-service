package com.redhat.qcon.insult;

import com.redhat.qcon.insult.services.insult.InsultServiceImpl;
import com.redhat.qcon.insult.services.reactivex.insult.InsultService;
import io.reactivex.Maybe;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.reactivex.ext.web.handler.CorsHandler;
import io.vertx.reactivex.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.serviceproxy.ServiceProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.vertx.core.http.HttpHeaders.*;
import static org.apache.http.entity.ContentType.APPLICATION_JSON;

public class MainVerticle extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(MainVerticle.class);

    JsonObject loadedConfig;

    Maybe<JsonObject> initConfigRetriever() {
        // Load the default configuration from the classpath
        LOG.info("Configuration store loading.");
        ConfigStoreOptions defaultOpts = new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject().put("path", "insult_default_config.json"));

        // Load container specific configuration from a specific file path inside of the
        // container
        ConfigStoreOptions localConfig = new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject().put("path", "/opt/docker_config.json"))
                .setOptional(true);

        // When running inside of Kubernetes, configure the application to also load
        // from a ConfigMap. This config is ONLY loaded when running inside of
        // Kubernetes or OpenShift
        ConfigStoreOptions confOpts = new ConfigStoreOptions()
                .setType("configmap")
                .setConfig(new JsonObject()
                        .put("name", "insult-config")
                        .put("optional", true)
                );

        // Add the default and container config options into the ConfigRetriever
        ConfigRetrieverOptions retrieverOptions = new ConfigRetrieverOptions()
                .addStore(defaultOpts)
                .addStore(localConfig)
                .addStore(confOpts);

        // Create the ConfigRetriever and return the Maybe when complete
        return ConfigRetriever.create(vertx, retrieverOptions).rxGetConfig().toMaybe();
    }

    /**
     * Begin the creation of the {@link OpenAPI3RouterFactory}
     * @param config The config loaded via the {@link ConfigRetriever}
     * @return An {@link OpenAPI3RouterFactory} {@link Future} to be used to complete
     *         the next Async step
     */
    private Maybe<OpenAPI3RouterFactory> provisionRouter(JsonObject config) {
        // Merge the loaded configuration into the config for this Verticle
        loadedConfig = config().mergeIn(config);

        if (LOG.isInfoEnabled()) {
            LOG.info("Config Loaded: {}", loadedConfig.encodePrettily());
        }

        // Instantiate the Insult Service and bind it to the event bus
        InsultServiceImpl nonRx = new InsultServiceImpl(vertx.getDelegate(), loadedConfig);
        new ServiceBinder(vertx.getDelegate())
                .setAddress("insult.service")
                .register(com.redhat.qcon.insult.services.insult.InsultService.class, nonRx);

        // Create the OpenAPI3RouterFactory using the API specification YAML file
        return OpenAPI3RouterFactory.rxCreate(vertx, "/insult-api-spec.yaml")
                .toMaybe();
    }

    /**
     * Wire the {@link OpenAPI3RouterFactory} into the HTTP Server
     * @param factory The {@link OpenAPI3RouterFactory} created in the previous step
     * @return An {@link HttpServer} if successful
     */
    Maybe<HttpServer> provisionHttpServer(OpenAPI3RouterFactory factory) {
        // Configure the HTTP Server options
        // - Listen on port 8080 on all interfaces using HTTP2 protocol
        HttpServerOptions httpOpts = new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(8080)
                .setLogActivity(true);

        InsultService service = InsultService
                .newInstance(new ServiceProxyBuilder(vertx.getDelegate())
                    .setAddress("insult.service")
                    .build(com.redhat.qcon.insult.services.insult.InsultService.class)
                );

        // Map out OpenAPI3 route to our Service Proxy implementation
        factory.addHandlerByOperationId("getInsult",
                ctx -> service.rxGetREST()
                        .doOnError(e -> errorHandler(ctx, e))
                        .subscribe(json -> sendResult(ctx, json)));

        // Map out OpenAPI3 route to our Service Proxy implementation
        factory.addHandlerByOperationId("health",
                ctx -> service.rxCheck()
                        .doOnError(e -> errorHandler(ctx, e))
                        .subscribe(json -> sendResult(ctx, json)));

        Router api = factory.getRouter();

        Router root = Router.router(vertx);

        CorsHandler corsHandler = CorsHandler.create(".*")
                .allowedHeader("Access-Control-Request-Method")
                .allowedHeader("Access-Control-Allow-Origin")
                .allowedHeader("Access-Control-Allow-Headers")
                .allowedHeader("Content-Type")
                .allowedMethod(HttpMethod.GET)
                .allowedMethod(HttpMethod.POST)
                .allowedMethod(HttpMethod.HEAD)
                .allowedMethod(HttpMethod.PUT)
                .allowedMethod(HttpMethod.OPTIONS)
                .allowedMethod(HttpMethod.CONNECT);

        root.route().order(0).handler(corsHandler);
        api.route().order(0).handler(corsHandler);

        root.mountSubRouter("/api/v1", api);

        BridgeOptions bOpts = new BridgeOptions()
                .addInboundPermitted(new PermittedOptions().setAddress("insult.service"))
                .addOutboundPermitted(new PermittedOptions().setAddress("kafka.service"))
                .addOutboundPermitted(new PermittedOptions().setAddress("insult.service"));

        SockJSHandler sockHandler = SockJSHandler.create(vertx).bridge(bOpts);

        root.route("/eventbus/*").handler(sockHandler);

        return vertx.createHttpServer(httpOpts)
                    .requestHandler(root::accept)
                    .rxListen()
                    .toMaybe();
    }

    /**
     * Send a successful HTTP response
     * @param ctx The {@link RoutingContext} of the request
     * @param json The {@link JsonObject} body to be sent in the response
     */
    private void sendResult(RoutingContext ctx, JsonObject json) {
        ctx.response()
            .putHeader(CONTENT_TYPE.toString(), APPLICATION_JSON.getMimeType())
            .setStatusCode(OK.code())
            .setStatusMessage(OK.reasonPhrase())
            .end(json.encodePrettily());
    }

    /**
     * Send an unsuccessful HTTP response
     * @param ctx The {@link RoutingContext} of the request
     * @param e The error which caused the failure
     */
    private void errorHandler(RoutingContext ctx, Throwable e) {
        ctx.response()
          .setStatusCode(INTERNAL_SERVER_ERROR.code())
          .setStatusMessage(INTERNAL_SERVER_ERROR.reasonPhrase())
          .end(e.getLocalizedMessage());
    }

    @Override
    public void start(Future<Void> startFuture) {
        initConfigRetriever()
                .flatMap(this::provisionRouter)
                .flatMap(this::provisionHttpServer)
                .doOnError(startFuture::fail)
                .subscribe(c -> startFuture.complete());
    }
}
