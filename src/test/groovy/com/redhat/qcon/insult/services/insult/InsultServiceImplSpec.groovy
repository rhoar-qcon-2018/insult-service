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

    static final SimulationSource GET_RESP_ONE = dsl(
            service('localhost')
                    .get("/api/v1/noun")
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())),
            service('localhost')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())))

    static final SimulationSource GET_RESP_TWO = dsl(
            service('localhost')
                    .get("/api/v1/noun")
                    .willReturn(serverError()),
            service('localhost')
                    .get("/api/v1/adjective")
                    .willReturn(serverError()))

    static final SimulationSource GET_RESP_THREE = dsl(
            service('localhost')
                    .get('/api/v1/noun')
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())
                                    .withDelay(1, TimeUnit.SECONDS)),
            service('localhost')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())))

    static final SimulationSource GET_RESP_FOUR = dsl(
            service('localhost')
                    .get('/api/v1/noun')
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())),
            service('localhost')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())
                                    .withDelay(1, TimeUnit.SECONDS)))

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
        setup: 'Http Client Config to work with Hoverfly'
            def httpClientConfig = new JsonObject()
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

        and: 'Create the service under test'
            InsultServiceImpl underTest = new InsultServiceImpl(vertx, httpClientConfig)

        and: 'AsyncConditions'
            def conds = new AsyncConditions(1)

        and: 'Service virtualization has been configured'
            hoverfly.simulate(simulation)

        and: 'We call the Service Proxy'
            underTest.getREST({ res ->
                conds.evaluate {
                    assert succeeded == res.succeeded()
                    def body = res?.result()
                    assert body?.getJsonArray('adjectives')?.getAt(0) == adjective
                    assert body?.getString('noun') == noun
                }
            })

        expect: 'The appropriate response to REST calls'
            conds.await(10)

        where: 'The following data is applied'
            simulation     | description       || succeeded | adjective   | noun
            GET_RESP_ONE   | 'Happy path'      || true      | 'adjective' | 'noun'
            GET_RESP_TWO   | 'Server error'    || true      | '[failure]' | '[failure]'
            GET_RESP_THREE | 'Slow adj reply'  || true      | 'adjective' | '[failure]'
            GET_RESP_FOUR  | 'Slow noun reply' || true      | '[failure]' | 'noun'
    }

    def cleanupSpec() {
        hoverfly.close()
        vertx.close()
    }
}
