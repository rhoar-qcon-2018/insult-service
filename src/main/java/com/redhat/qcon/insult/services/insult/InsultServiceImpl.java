package com.redhat.qcon.insult.services.insult;

import com.redhat.qcon.kafka.services.reactivex.KafkaService;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.CompositeFuture;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.apache.http.client.HttpResponseException;
import org.jetbrains.annotations.NotNull;

import static java.lang.String.format;

public class InsultServiceImpl implements InsultService {

    Vertx vertx;
    WebClient nounClient, adjClient;
    KafkaService kafka;

    /**
     * Default constructor
     * @param vertx The Vert.x instance to be used
     * @param config The {@link JsonObject} configuration for this service
     */
    public InsultServiceImpl(io.vertx.core.Vertx vertx, JsonObject config) {

        kafka = KafkaService.createProxy(Vertx.newInstance(vertx), "kafka.service");

        JsonObject nounConfig = config.getJsonObject("noun");
        JsonObject adjConfig = config.getJsonObject("adjective");
        this.vertx = Vertx.newInstance(vertx);
        WebClientOptions nounClientOpts = new WebClientOptions(nounConfig)
                .setLogActivity(true);
        WebClientOptions adjClientOpts = new WebClientOptions(adjConfig)
                .setLogActivity(true);
        nounClient = WebClient.create(this.vertx, nounClientOpts);
        adjClient = WebClient.create(this.vertx, adjClientOpts);
    }

    /**
     * Request adjectives and a noun from the other microservices
     * @param insultGetHandler A {@link Handler} callback for the results
     */
    @Override
    public void getREST(Handler<AsyncResult<JsonObject>> insultGetHandler) {
        // Request 2 adjectives and a noun in parallel, then handle the results
        CompositeFuture.all(getNoun(), getAdjective(), getAdjective())
                .rxSetHandler()
                .map(InsultServiceImpl::mapResultToError)   // Map errors to an exception
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
        for (int i=0; i<=cf.size(); i++) {
            JsonObject item = cf.resultAt(i);
            if (item.containsKey("adjective")) {
                adjectives.add(item.getString("adjective"));
            } else {
                insult.put("noun", item.getString("noun"));
            }
        }
        insult.put("adjectives", adjectives);

        return Future.succeededFuture(insult);
    }

    /**
     * Requests an adjective from the appropriate microservice and returns a future with the result
     * @return A {@link Future} of type {@link JsonObject} which will contain an adjective on success
     */
    io.vertx.reactivex.core.Future<JsonObject> getAdjective() {
        io.vertx.reactivex.core.Future<JsonObject> fut = io.vertx.reactivex.core.Future.future();
        adjClient.get("/api/v1/adjective")
                .timeout(3000)
                .rxSend()
                .map(InsultServiceImpl::mapStatusToError)
                .map(HttpResponse::bodyAsJsonObject)
                .doOnError(fut::fail)
                .subscribe(fut::complete);
        return fut;
    }

    /**
     * Requests a noun from the appropriate microservice and returns a future with the result
     * @return A {@link Future} of type {@link JsonObject} which will contain a noun on success
     */
    io.vertx.reactivex.core.Future<JsonObject> getNoun() {
        io.vertx.reactivex.core.Future<JsonObject> fut = io.vertx.reactivex.core.Future.future();
        nounClient.get("/api/v1/noun")
                .timeout(3000)
                .rxSend()
                .map(InsultServiceImpl::mapStatusToError)
                .map(HttpResponse::bodyAsJsonObject)
                .doOnError(fut::fail)
                .subscribe(fut::complete);
        return fut;
    }

    /**
     * Use the {@link KafkaService} event bus proxy to make calls to the Kafka microservice
     * @param insult An insult made up of 2 adjectives and a noun
     * @param handler A handler to be called
     */
    @Override
    public InsultService publish(JsonObject insult, Handler<AsyncResult<Void>> handler) {
        Future<Void> fut = Future.future();
        handler.handle(fut);
        kafka.rxPublish(insult)
                .toObservable()
                .doOnError(fut::fail)
                .subscribe(v -> fut.complete());
        return this;
    }

    /**
     * When the {@link CompositeFuture} is failed, throws an exception in order to interrups the RxJava stream processing
     * @param res The {@link CompositeFuture} to be processed
     * @return The same as the input as long as the {@link CompositeFuture} was succeeded
     * @throws Exception If the {@link CompositeFuture} is failed
     */
    private static final CompositeFuture mapResultToError(CompositeFuture res) throws Exception {
        if (res.succeeded()) {
            return res;
        }
        throw new Exception(res.cause());
    }

    /**
     * Maps HTTP error status codes to exceptions to interrupt the RxJava stream processing and trigger an error handler
     * @param r The {@link HttpResponse} to be checked
     * @return The same as the input if the response code is 2XX
     * @throws Exception If the {@link HttpResponse} code is 4XX or 5XX
     */
    private static final HttpResponse<Buffer> mapStatusToError(HttpResponse<Buffer> r) throws Exception {
        if (r.statusCode()>=400) {
            throw new Exception(format("%d: %s\n%s", r.statusCode(), r.statusMessage(), r.bodyAsString()));
        } else {
            return r;
        }
    }
}
