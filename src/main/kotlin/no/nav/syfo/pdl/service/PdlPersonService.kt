package no.nav.syfo.pdl.service

import no.nav.syfo.azuread.AccessTokenClient
import no.nav.syfo.log
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.client.model.ResponseData
import no.nav.syfo.pdl.model.Navn
import no.nav.syfo.pdl.model.PdlPerson

class PdlPersonService(
    private val pdlClient: PdlClient,
    private val accessTokenClient: AccessTokenClient,
    private val pdlScope: String,
) {
    suspend fun getPersoner(fnrs: List<String>): Map<String, PdlPerson> {
        val accessToken = accessTokenClient.getAccessToken(pdlScope)

        val pdlResponse = pdlClient.getPersoner(fnrs = fnrs, token = accessToken)
        if (pdlResponse.errors != null) {
            pdlResponse.errors.forEach {
                log.error("PDL returnerte feilmelding: ${it.message}, ${it.extensions?.code}")
                it.extensions?.details?.let { details ->
                    log.error(
                        "Type: ${details.type}, cause: ${details.cause}, policy: ${details.policy}"
                    )
                }
            }
        }
        if (
            pdlResponse.data.hentPersonBolk == null ||
                pdlResponse.data.hentPersonBolk.isNullOrEmpty()
        ) {
            log.error("Fant ikke identer i PDL")
            throw IllegalStateException("Fant ingen identer i PDL!")
        }
        pdlResponse.data.hentPersonBolk.forEach {
            if (it.code != "ok") {
                log.warn("Mottok feilkode ${it.code} fra PDL for en eller flere personer")
            }
        }
        return pdlResponse.data.toPdlPersonMap()
    }

    private fun ResponseData.toPdlPersonMap(): Map<String, PdlPerson> {
        return hentPersonBolk!!.associate {
            it.ident to
                PdlPerson(
                    navn = getNavn(it.person?.navn?.first()),
                )
        }
    }

    private fun getNavn(navn: no.nav.syfo.pdl.client.model.Navn?): Navn =
        Navn(
            fornavn = navn?.fornavn ?: "Fornavn",
            mellomnavn = navn?.mellomnavn,
            etternavn = navn?.etternavn ?: "Etternavn"
        )
}
