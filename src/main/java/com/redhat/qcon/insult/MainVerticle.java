package com.redhat.qcon.insult;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.ext.web.Router;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Future<Void> startFuture) {
        Router router = Router.router(vertx);

        router.get("/api/v1/insult").handler(ctx ->
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end("testinsult"));

        vertx.createHttpServer().requestHandler(router::accept).listen(8080, res -> {
            if (res.succeeded()) {
                startFuture.complete();
            } else {
                startFuture.fail(res.cause());
            }
        });
    }
}