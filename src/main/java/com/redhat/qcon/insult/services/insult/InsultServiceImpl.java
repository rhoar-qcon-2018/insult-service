package com.redhat.qcon.insult.services.insult;

import com.redhat.qcon.kafka.services.reactivex.KafkaService;
import io.reactivex.Maybe;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ProxyOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.circuitbreaker.CircuitBreaker;
import io.vertx.reactivex.core.CompositeFuture;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;

public class InsultServiceImpl implements InsultService {

    private static final Logger LOG = LoggerFactory.getLogger(InsultServiceImpl.class);

    private static final int HTTP_CLIENT_TIMEOUT = 2000;
    private static final int CIRCUIT_TIMEOUT = 1000;
    Vertx vertx;
    WebClient nounClient, adjClient;
    KafkaService kafka;
    CircuitBreaker adjBreaker;
    CircuitBreaker nounBreaker;
    JsonObject config;

    /**
     * Default constructor
     * @param vertx The Vert.x instance to be used
     * @param config The {@link JsonObject} configuration for this service
     */
    public InsultServiceImpl(io.vertx.core.Vertx vertx, JsonObject config) {
        this.config = config;

        kafka = KafkaService.createProxy(Vertx.newInstance(vertx), "kafka.service");

        this.vertx = Vertx.newInstance(vertx);

        WebClientOptions clientOpts = new WebClientOptions()
                .setLogActivity(false);
        if (config.containsKey("proxyOptions")) {
            clientOpts.setProxyOptions(new ProxyOptions(config.getJsonObject("proxyOptions")));
        }
        nounClient = WebClient.create(this.vertx, clientOpts);
        adjClient = WebClient.create(this.vertx, clientOpts);

        CircuitBreakerOptions breakerOpts = new CircuitBreakerOptions()
                                                    .setFallbackOnFailure(true)
                                                    .setMaxFailures(3)
                                                    .setMaxRetries(3)
                                                    .setResetTimeout(15000)
                                                    .setTimeout(CIRCUIT_TIMEOUT);

        adjBreaker = CircuitBreaker
                        .create("adjBreaker", Vertx.newInstance(vertx), breakerOpts)
                        .openHandler(t -> new JsonObject().put("adj", "[open]"))
                        .fallback(t -> new JsonObject().put("adj", "[failure]"))
                        .reset();

        nounBreaker = CircuitBreaker
                        .create("nounBreaker", Vertx.newInstance(vertx), breakerOpts)
                        .openHandler(t -> circuitBreakerHandler("noun", "[open]"))
                        .fallback(t -> circuitBreakerHandler("noun", "[failure]"))
                        .reset();
    }

    public JsonObject circuitBreakerHandler(String key, String value) {
        LOG.error("Timeout requesting '{}', returned '{}'", key, value);
        return new JsonObject().put(key, value);
    }

    /**
     * Request adjectives and a noun from the other microservices
     * @param insultGetHandler A {@link Handler} callback for the results
     */
    @Override
    public void getREST(Handler<AsyncResult<JsonObject>> insultGetHandler) {
        // Request 2 adjectives and a noun in parallel, then handle the results
        LOG.info("Received request");
        CompositeFuture.all(getNoun(), getAdjective(), getAdjective())
                .rxSetHandler()
                .flatMapMaybe(InsultServiceImpl::mapResultToError)   // Map errors to an exception
                .map(InsultServiceImpl::buildInsult)        // Combine the 3 results into a single JSON object
                .onErrorReturn(Future::failedFuture)        // When an exception happens, map it to a failed future
                .subscribe(insultGetHandler::handle);       // Map successful JSON to a succeeded future
    }

    /**
     * Take results of {@link CompositeFuture} and return a composed {@link JsonObject} containing the insult components
     * @param cf An instance of {@link CompositeFuture} which MUST be succeeded, otherwise it would have been filtered
     * @return A {@link JsonObject} containing a noun and an array of adjectives.
     */
    private static AsyncResult<JsonObject> buildInsult(CompositeFuture cf) {
        JsonObject insult = new JsonObject();
        JsonArray adjectives = new JsonArray();

        // Because there is no garanteed order of the returned futures, we need to parse the results
        for (int i=0; i<cf.size(); i++) {
            JsonObject item = cf.resultAt(i);
            if (item.containsKey("adj")) {
                adjectives.add(item.getString("adj"));
            } else {
                insult.put("noun", item.getString("noun"));
            }
        }
        insult.put("adj1", adjectives.getString(0));
        insult.put("adj2", adjectives.getString(1));

        return Future.succeededFuture(insult);
    }

    /**
     * Requests an adjective from the appropriate microservice and returns a future with the result
     * @return A {@link Future} of type {@link JsonObject} which will contain an adjective on success
     */
    io.vertx.reactivex.core.Future<JsonObject> getAdjective() {
        String host = config.getJsonObject("adjective").getString("host");
        int port = config.getJsonObject("adjective").getInteger("port");
        return adjBreaker.execute(fut ->
            adjClient.get(port, host, "/api/v1/adjective")
                    .timeout(HTTP_CLIENT_TIMEOUT)
                    .rxSend()
                    .doOnError(e -> LOG.error("REST Request failed", e))
                    .flatMapMaybe(InsultServiceImpl::mapStatusToError)
                    .map(HttpResponse::bodyAsJsonObject)
                    .subscribe(fut::complete, fut::fail));
    }

    /**
     * Requests a noun from the appropriate microservice and returns a future with the result
     * @return A {@link Future} of type {@link JsonObject} which will contain a noun on success
     */
    io.vertx.reactivex.core.Future<JsonObject> getNoun() {
        String host = config.getJsonObject("noun").getString("host");
        int port = config.getJsonObject("noun").getInteger("port");
        return nounBreaker.execute(fut ->
            nounClient.get(port, host, "/api/v1/noun")
                    .timeout(HTTP_CLIENT_TIMEOUT)
                    .rxSend()
                    .doOnError(e -> LOG.error("REST Request failed", e))
                    .flatMapMaybe(InsultServiceImpl::mapStatusToError)
                    .map(HttpResponse::bodyAsJsonObject)
                    .subscribe(fut::complete, fut::fail));
    }

    /**
     * Use the {@link KafkaService} event bus proxy to make calls to the Kafka microservice
     * @param insult An insult made up of 2 adjectives and a noun
     * @param handler A handler to be called
     */
    @Override
    public InsultService publish(JsonObject insult, Handler<AsyncResult<Void>> handler) {
        Future<Void> fut = Future.future();
        fut.setHandler(handler);
        kafka.rxPublish(insult)
                .toObservable()
                .doOnError(fut::fail)
                .subscribe(v -> fut.completer());
        return this;
    }

    /**
     * When the {@link CompositeFuture} is failed, throws an exception in order to interrups the RxJava stream processing
     * @param res The {@link CompositeFuture} to be processed
     * @return The same as the input if the {@link CompositeFuture} was succeeded
     */
    static final Maybe<CompositeFuture> mapResultToError(CompositeFuture res) {
        if (res.succeeded()) {
            return Maybe.just(res);
        } else {
            for (int x=0; x<3; x++) {
                LOG.error("Failed to request insult components", res.cause(x));
            }
            return Maybe.empty();
        }

    }

    /**
     * Maps HTTP error status codes to exceptions to interrupt the RxJava stream
     * processing and trigger an error handler
     * @param r The {@link HttpResponse} to be checked
     * @return The same as the input if the response code is 2XX, otherwise an Exception
     */
    private static final Maybe<HttpResponse<Buffer>> mapStatusToError(HttpResponse<Buffer> r) {
        if (r.statusCode()>=400) {
            String errorMessage = format("%d: %s\n%s",
                    r.statusCode(),
                    r.statusMessage(),
                    r.bodyAsString());
            Exception clientException = new Exception(errorMessage);
            return Maybe.error(clientException);
        } else {
            return Maybe.just(r);
        }
    }

    /**
     * Check the health of this service
     * @param healthCheckHandler A {@link Handler} callback for the results
     */
    @Override
    public void check(Handler<AsyncResult<JsonObject>> healthCheckHandler) {
        JsonObject status = new JsonObject();

        String nounState = nounBreaker.state().name();
        String adjState = adjBreaker.state().name();
        status.put("noun", nounState)
                .put("adj", adjState);

        if (nounState.contentEquals("OPEN") || adjState.contentEquals("OPEN")) {
            status.put("status", "OK");
        } else {
            status.put("status", "DEGRADED");
        }
        healthCheckHandler.handle(Future.succeededFuture(status));
    }
}
