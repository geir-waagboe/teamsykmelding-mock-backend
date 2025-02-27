package no.nav.syfo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.apache.Apache
import io.ktor.client.engine.apache.ApacheEngineConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.jackson.jackson
import io.prometheus.client.hotspot.DefaultExports
import no.nav.syfo.application.ApplicationServer
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.createApplicationEngine
import no.nav.syfo.application.exception.ServiceUnavailableException
import no.nav.syfo.azuread.AccessTokenClient
import no.nav.syfo.kafka.aiven.KafkaUtils
import no.nav.syfo.kafka.toProducerConfig
import no.nav.syfo.legeerklaering.LegeerklaeringService
import no.nav.syfo.mq.connectionFactory
import no.nav.syfo.narmesteleder.NarmestelederService
import no.nav.syfo.narmesteleder.kafka.NlResponseProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseKafkaMessage
import no.nav.syfo.oppgave.OppgaveClient
import no.nav.syfo.papirsykmelding.PapirsykmeldingService
import no.nav.syfo.papirsykmelding.client.DokarkivClient
import no.nav.syfo.papirsykmelding.client.NorskHelsenettClient
import no.nav.syfo.papirsykmelding.client.SyfosmpapirreglerClient
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.service.PdlPersonService
import no.nav.syfo.sykmelding.SlettSykmeldingService
import no.nav.syfo.sykmelding.SykmeldingService
import no.nav.syfo.sykmelding.client.SyfosmregisterClient
import no.nav.syfo.sykmelding.client.SyfosmreglerClient
import no.nav.syfo.sykmelding.kafka.SykmeldingStatusKafkaProducer
import no.nav.syfo.sykmelding.kafka.TombstoneKafkaProducer
import no.nav.syfo.utenlandsk.opprettJournalpostservice.UtenlandskSykmeldingService
import no.nav.syfo.util.JacksonKafkaSerializer
import no.nav.syfo.util.JacksonNullableKafkaSerializer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val log: Logger = LoggerFactory.getLogger("no.nav.syfo.teamsykmelding-mock-backend")
val objectMapper: ObjectMapper =
    ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }

fun main() {
    val env = Environment()
    val serviceUser = ServiceUser()
    DefaultExports.initialize()
    val applicationState = ApplicationState()

    val connection =
        connectionFactory(env)
            .apply {
                sslSocketFactory = null
                sslCipherSuite = null
            }
            .createConnection(serviceUser.username, serviceUser.password)
    connection.start()

    val config: HttpClientConfig<ApacheEngineConfig>.() -> Unit = {
        install(ContentNegotiation) {
            jackson {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                when (exception) {
                    is SocketTimeoutException ->
                        throw ServiceUnavailableException(exception.message)
                }
            }
        }
        install(HttpRequestRetry) {
            constantDelay(100, 0, false)
            retryOnExceptionIf(3) { request, throwable ->
                log.warn("Caught exception ${throwable.message}, for url ${request.url}")
                true
            }
            retryIf(maxRetries) { request, response ->
                if (response.status.value.let { it in 500..599 }) {
                    log.warn(
                        "Retrying for statuscode ${response.status.value}, for url ${request.url}"
                    )
                    true
                } else {
                    false
                }
            }
        }
        install(HttpTimeout) {
            socketTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 30_000
        }
    }
    val httpClient = HttpClient(Apache, config)

    val accessTokenClient =
        AccessTokenClient(env.aadAccessTokenUrl, env.clientId, env.clientSecret, httpClient)
    val pdlClient =
        PdlClient(
            httpClient,
            env.pdlGraphqlPath,
            PdlClient::class
                .java
                .getResource("/graphql/getPerson.graphql")!!
                .readText()
                .replace(Regex("[\n\t]"), ""),
        )
    val pdlPersonService = PdlPersonService(pdlClient, accessTokenClient, env.pdlScope)

    val dokarkivClient =
        DokarkivClient(
            url = env.dokarkivUrl,
            accessTokenClient = accessTokenClient,
            scope = env.dokarkivScope,
            httpClient = httpClient,
        )

    val syfosmregisterClient =
        SyfosmregisterClient(
            syfosmregisterUrl = env.syfosmregisterUrl,
            accessTokenClient = accessTokenClient,
            syfosmregisterScope = env.syfosmregisterScope,
            httpClient = httpClient,
        )

    val syfosmreglerClient =
        SyfosmreglerClient(
            syfosmreglerUrl = env.syfosmreglerUrl,
            accessTokenClient = accessTokenClient,
            syfosmreglerScope = env.syfosmreglerScope,
            httpClient = httpClient,
        )

    val syfosmpapirreglerClient =
        SyfosmpapirreglerClient(
            syfosmpapirreglerUrl = env.syfosmpapirreglerUrl,
            accessTokenClient = accessTokenClient,
            syfosmpapirreglerScope = env.syfosmpapirreglerScope,
            httpClient = httpClient,
        )

    val norskHelsenettClient =
        NorskHelsenettClient(
            norskHelsenettUrl = env.norskHelsenettUrl,
            accessTokenClient = accessTokenClient,
            norskHelsenettScope = env.norskHelsenettScope,
            httpClient = httpClient,
        )

    val oppgaveClient =
        OppgaveClient(
            url = env.oppgaveUrl,
            accessTokenClient = accessTokenClient,
            scope = env.oppgaveScope,
            httpClient = httpClient,
        )

    val producerProperties =
        KafkaUtils.getAivenKafkaConfig("nl-response-producer")
            .toProducerConfig(
                env.applicationName,
                JacksonKafkaSerializer::class,
                StringSerializer::class
            )

    val kafkaProducer = KafkaProducer<String, NlResponseKafkaMessage>(producerProperties)
    val nlResponseKafkaProducer = NlResponseProducer(kafkaProducer, env.narmestelederTopic)

    val tombstoneProducer =
        KafkaProducer<String, Any?>(
            KafkaUtils.getAivenKafkaConfig("tombstone-producer")
                .toProducerConfig("env.applicationName", JacksonNullableKafkaSerializer::class),
        )
    val tombstoneKafkaProducer =
        TombstoneKafkaProducer(
            tombstoneProducer,
            listOf(env.papirSmRegistreringTopic, env.manuellTopic)
        )
    val sykmeldingStatusKafkaProducer =
        SykmeldingStatusKafkaProducer(KafkaProducer(producerProperties), env.sykmeldingStatusTopic)

    val narmestelederService = NarmestelederService(nlResponseKafkaProducer, pdlPersonService)
    val slettSykmeldingService =
        SlettSykmeldingService(
            syfosmregisterClient,
            sykmeldingStatusKafkaProducer,
            tombstoneKafkaProducer
        )
    val sykmeldingService =
        SykmeldingService(pdlPersonService, connection, env.sykmeldingQueue, syfosmreglerClient)
    val legeerklaeringService =
        LegeerklaeringService(pdlPersonService, connection, env.legeerklaeringQueue)
    val papirsykmeldingService =
        PapirsykmeldingService(dokarkivClient, syfosmpapirreglerClient, norskHelsenettClient)
    val utenlandskSykmeldingService = UtenlandskSykmeldingService(dokarkivClient, oppgaveClient)

    val applicationEngine =
        createApplicationEngine(
            env,
            applicationState,
            narmestelederService,
            sykmeldingService,
            slettSykmeldingService,
            legeerklaeringService,
            papirsykmeldingService,
            utenlandskSykmeldingService,
        )
    val applicationServer = ApplicationServer(applicationEngine, applicationState)
    applicationServer.start()
}
