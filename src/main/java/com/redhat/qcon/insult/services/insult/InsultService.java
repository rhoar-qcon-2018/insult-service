package com.redhat.qcon.insult.services.insult;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@ProxyGen
@VertxGen
public interface InsultService {

    static InsultService create(Vertx vertx) {
        return new InsultServiceImpl(vertx, vertx.getOrCreateContext().config());
    }

    static InsultService createProxy(Vertx vertx, String address) {
        return new InsultServiceVertxEBProxy(vertx, address);
    }

    // Business logic methods here!!

    /**
     * Retrieve an insult from the child services and build the insult payload
     * @param insultGetHandler A {@link Handler} callback for the results
     */
    void getREST(Handler<AsyncResult<JsonObject>> insultGetHandler);

    /**
     * Publish a "liked" insult to the Kafka queue to be distributed to all of the other clusters
     * @param insult An insult made up of 2 adjectives and a noun
     * @param insultPublishHandler A {@link Handler} callback for the results
     */
    void publish(JsonObject insult, Handler<AsyncResult<Void>> insultPublishHandler);
}
