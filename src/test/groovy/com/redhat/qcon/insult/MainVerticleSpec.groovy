package com.redhat.qcon.insult

import io.vertx.core.Future
import io.vertx.core.Vertx
import spock.lang.Specification
import spock.util.concurrent.AsyncConditions

class MainVerticleSpec extends Specification {

    def 'Test Vert.x configuration loading'() {
        given: 'An instance of Vert.x'  // (1)
            def vertx = Vertx.vertx()
        and: 'An instance of a Vert.x Future'  // (2)
            def fut = Future.future()
        and: '''An instance of Spock's AsyncConditions'''
            def async = new AsyncConditions(1)
        and: 'The Verticle under test'
            def underTest = new MainVerticle()

        when: 'We attempt to deploy the main Verticle'  // (3)
            vertx.deployVerticle(underTest, fut.completer())

        then: 'Expect that the correct configuration is found and loaded'
            fut.setHandler({ res ->
                async.evaluate {
                    def config = underTest.loadedConfig
                    assert res.succeeded() // (4)
                    assert config.getJsonObject('noun').getInteger('port') == 80 // (5)
                    assert config.getJsonObject('adjective').getInteger('port') == 80 // (6)
                    assert config.getJsonObject('http').getInteger('port') == 8080 // (7)
                }
            })

        cleanup: 'Await the async operations'  // (8)
            async.await(3600)
            vertx.close()
    }
}
