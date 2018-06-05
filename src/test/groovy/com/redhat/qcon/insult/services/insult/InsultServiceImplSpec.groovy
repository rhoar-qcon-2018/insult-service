package com.redhat.qcon.insult.services.insult

import io.specto.hoverfly.junit.core.Hoverfly
import io.specto.hoverfly.junit.core.SimulationSource
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.AsyncConditions

import java.util.concurrent.TimeUnit

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON
import static io.specto.hoverfly.junit.core.HoverflyConfig.localConfigs
import static io.specto.hoverfly.junit.core.HoverflyMode.SIMULATE
import static io.specto.hoverfly.junit.core.SimulationSource.*
import static io.specto.hoverfly.junit.dsl.HoverflyDsl.*
import static io.specto.hoverfly.junit.dsl.ResponseCreators.serverError
import static io.specto.hoverfly.junit.dsl.ResponseCreators.success

class InsultServiceImplSpec extends Specification {
    @Shared
    Hoverfly hoverfly

    @Shared
    Vertx vertx

    @Shared
    JsonObject proxyOptions

    static final String NOUN_RESPONSE_BODY_ONE = new JsonObject().put('noun', 'noun').encodePrettily()
    static final String ADJ_RESPONSE_BODY_ONE = new JsonObject().put('adj', 'adjective').encodePrettily()

    static final SimulationSource GET_RESPONSE_ONE = dsl(
            service('localhost')
                    .get("/api/v1/noun")
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE, APPLICATION_JSON.toString())),
            service('localhost')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE, APPLICATION_JSON.toString())))

    static final SimulationSource GET_RESPONSE_TWO = dsl(
            service('localhost')
                    .get("/api/v1/noun")
                    .willReturn(serverError()))

    static final SimulationSource GET_RESPONSE_THREE = dsl(
            service('localhost')
                    .andDelay(10, TimeUnit.SECONDS).forAll(),
            service('localhost')
                    .get('/api/v1/noun')
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE, APPLICATION_JSON.toString())),
            service('localhost')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE, APPLICATION_JSON.toString())))

    def setupSpec() {
        System.setProperty('org.slf4j.simpleLogger.defaultLogLevel', 'debug')
        def hoverflyConfig = localConfigs().proxyLocalHost().captureAllHeaders()
        hoverfly = new Hoverfly(hoverflyConfig, SIMULATE)
        hoverfly.start()
        proxyOptions = new JsonObject()
                .put('host', 'localhost')
                .put('port', hoverfly.hoverflyConfig.proxyPort)
                .put('type', 'HTTP')
        vertx = Vertx.vertx()
    }

    def setup() {
        hoverfly.resetJournal()
    }

    @Unroll
    def 'Test getting a noun: #description'() {
        setup: 'Configure service under test'
            InsultServiceImpl underTest = new InsultServiceImpl(vertx,
                new JsonObject()
                        .put('noun',
                            new JsonObject().put('host', 'localhost')
                                    .put('ssl', false)
                                    .put('port', 80)
                                    .put('proxyOptions', proxyOptions)
                        )
                        .put('adjective',
                            new JsonObject().put('host', 'localhost')
                                    .put('ssl', false)
                                    .put('port', 80)
                                    .put('proxyOptions', proxyOptions)
                        )
                )
        and: 'AsyncConditions'
            def async = new AsyncConditions(1)
        and: 'Service virtualization has been configured'
            hoverfly.simulate(simulation)

        expect: 'The appropriate response to REST calls'
            underTest.getREST({ res ->
                async.evaluate {
                    if (responseCode == 200) {
                        res.succeeded()
                        res.result().toString() == body
                    } else {
                        res.failed()
                    }
                }
            })
            async.await(15)

        where: 'The following data is applied'
            simulation         | description    || responseCode | body
            GET_RESPONSE_ONE   | 'Happy path'   || 200          | NOUN_RESPONSE_BODY_ONE
            GET_RESPONSE_TWO   | 'Server error' || 500          | ""
            GET_RESPONSE_THREE | 'Slow reply'   || 200          | NOUN_RESPONSE_BODY_ONE
    }

    def cleanupSpec() {
        hoverfly.close()
        vertx.close()
    }
}
