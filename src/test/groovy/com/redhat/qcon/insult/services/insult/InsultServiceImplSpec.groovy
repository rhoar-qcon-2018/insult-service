package com.redhat.qcon.insult.services.insult

import io.specto.hoverfly.junit.core.Hoverfly
import io.specto.hoverfly.junit.core.SimulationSource
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.net.ProxyOptions
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.reactivex.circuitbreaker.CircuitBreaker
import io.vertx.reactivex.core.CompositeFuture
import io.vertx.reactivex.ext.web.client.WebClient
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
import static io.vertx.circuitbreaker.CircuitBreakerState.*

class InsultServiceImplSpec extends Specification {

    @Shared
    Hoverfly hoverfly

    @Shared
    Vertx vertx

    @Shared
    JsonObject proxyOptions

    static final String NOUN_RESPONSE_BODY_ONE = new JsonObject().put('noun', 'noun').encodePrettily()
    static final String ADJ_RESPONSE_BODY_ONE = new JsonObject().put('adjective', 'adjective').encodePrettily()

    static final SimulationSource GET_RESP_ONE = dsl(
            service('noun-service')
                    .get("/api/v1/noun")
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())),
            service('adjective-service')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())))

    static final SimulationSource GET_RESP_TWO = dsl(
            service('noun-service')
                    .get("/api/v1/noun")
                    .willReturn(serverError()),
            service('adjective-service')
                    .get("/api/v1/adjective")
                    .willReturn(serverError()))

    static final SimulationSource GET_RESP_THREE = dsl(
            service('noun-service')
                    .get('/api/v1/noun')
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())
                                    .withDelay(10, TimeUnit.SECONDS)),
            service('adjective-service')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())))

    static final SimulationSource GET_RESP_FOUR = dsl(
            service('noun-service')
                    .get('/api/v1/noun')
                    .willReturn(success(NOUN_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())),
            service('adjective-service')
                    .get("/api/v1/adjective")
                    .willReturn(success(ADJ_RESPONSE_BODY_ONE,
                                        APPLICATION_JSON.toString())
                                    .withDelay(10, TimeUnit.SECONDS)))

    @Shared
    JsonObject httpClientConfig


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

        httpClientConfig = new JsonObject()
            .put('noun',
                new JsonObject().put('host', 'noun-service')
                        .put('ssl', false)
                        .put('port', 80)
            )
            .put('adjective',
                new JsonObject().put('host', 'adjective-service')
                        .put('ssl', false)
                        .put('port', 80)
            )
            .put('proxyOptions', proxyOptions)
    }

    def setup() {
        hoverfly.resetJournal()
    }

    @Unroll
    def 'Test getting an insult: #description'() {
        setup: 'Create the service under test'
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
                    assert body?.getString('adj1') == adjective
                    assert body?.getString('adj2') == adjective
                    assert body?.getString('noun') == noun
                }
            })

        expect: 'The appropriate response to REST calls'
            conds.await(15)
            hoverfly.resetJournal()
            hoverfly.reset()

        where: 'The following data is applied'
            simulation     | description       || succeeded | adjective   | noun
            GET_RESP_ONE   | 'Happy path'      || true      | 'adjective' | 'noun'
            GET_RESP_TWO   | 'Server error'    || true      | '[failure]' | '[failure]'
            GET_RESP_TWO   | 'Server error'    || true      | '[failure]' | '[failure]'
            GET_RESP_FOUR  | 'Slow noun reply' || true      | '[failure]' | 'noun'
    }

    @Unroll
    def "Test health check endpoint: #description"() {
        setup: 'Create Mocks for circuit breakers'
            def adjBreaker = Mock(CircuitBreaker) {
                1 * state() >> adjective
            }
            def nounBreaker = Mock(CircuitBreaker) {
                1 * state() >> noun
            }

        and: 'Create an instance of the service under test'
            def underTest = new InsultServiceImpl(vertx, httpClientConfig)

        and: 'Replace the circuit breakers with Mocks'
            underTest.adjBreaker = adjBreaker
            underTest.nounBreaker = nounBreaker

        and: 'An instance of AsyncConditions'
            def conds = new AsyncConditions(1)

        expect: 'We call the health check method'
            underTest.check({ res ->
                conds.evaluate {
                    assert res.succeeded() == status
                    assert res.result().getString("noun") == noun
                    assert res.result().getString("adj") == adjective
                }
            })

        where: 'The following data table is used.'
            description            | status     | noun      | adjective
            'Both breakers closed' | true       | CLOSED    | CLOSED
            'Adj breaker open'     | false      | CLOSED    | OPEN
            'Noun breaker open'    | false      | OPEN      | CLOSED
            'Both breakers open'   | false      | OPEN      | OPEN
    }

    def "Test mapResultToError sad path"() {
        given: "Mock CompositeFuture"
            def cf = Mock(CompositeFuture)

        when: "Method under test is called"
            def maybe = InsultServiceImpl.mapResultToError(cf)
            assert maybe.isEmpty().blockingGet() == true

        then:
            1 * cf.succeeded() >> false
            1 * cf.cause(0) >> new Exception('Test1')
            1 * cf.cause(1) >> new Exception('Test2')
            1 * cf.cause(2) >> new Exception('Test3')
    }

    def cleanupSpec() {
        hoverfly.close()
        vertx.close()
    }
}
