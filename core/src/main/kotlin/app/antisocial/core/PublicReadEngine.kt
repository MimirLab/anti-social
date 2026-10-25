/* anti social, GPL-3.0-or-later. Byte-engine contract, not network permission. */
package app.antisocial.core

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.security.Principal
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** A platform implementation handles bytes only. Gateway owns every socket. */
interface PublicReadEngineFactory {
    fun supports(cohort:Int):Boolean
    fun open(cohort:Int,host:String,port:Int,path:String,headers:List<Pair<String,String>>,limit:Int,
        verify:(PublicReadPeer)->Boolean,decoded:(Int)->Unit):PublicReadByteEngine
    /** Test factories can supply their owned fixture roots. Normal factories
     * inherit platform validation, including HTTPS identity and stapled data. */
    fun verifyChain(manager:X509TrustManager,session:PublicReadTlsSession) {
        PublicReadTrust.verify(manager,session)
    }
}

interface PublicReadByteEngine:Closeable {
    /** -1=failure, 0=await input, 1=drain output, 2=complete. */
    fun step():Int
    fun feed(bytes:ByteArray,count:Int)
    fun output(bytes:ByteArray):Int
    fun result():PublicReadResult
}
data class PublicReadResult(val status:Int,val headers:List<Pair<String,String>>,val body:ByteArray)
data class PublicReadPeer(val chain:List<ByteArray>,val protocol:String,val cipher:String,val ocsp:ByteArray,val sct:ByteArray)

/** Real handshake evidence exposed to the existing platform verifier. This
 * creates no TLS connection and makes no trust decision by itself. */
object PublicReadTrust {
    fun session(host:String,port:Int,peer:PublicReadPeer):PublicReadTlsSession {
        require(peer.chain.size in 1..16&&peer.chain.sumOf {it.size.toLong()}<=262144&&peer.ocsp.size<=65536&&peer.sct.size<=65536)
        require(peer.protocol in setOf("TLSv1.2","TLSv1.3")&&peer.cipher.matches(Regex("TLS_[A-Z0-9_]{1,100}")))
        val parser=CertificateFactory.getInstance("X.509")
        val certs=peer.chain.map {data->ByteArrayInputStream(data).use {input->
            val cert=parser.generateCertificate(input) as X509Certificate
            require(input.available()==0){"Trailing certificate bytes"};cert
        }}.toTypedArray()
        return PublicReadTlsSession(host,port,peer.protocol,peer.cipher,certs,peer.ocsp.clone(),peer.sct.clone())
    }
    fun verify(manager:X509TrustManager,session:PublicReadTlsSession) {
        val extended=manager as? X509ExtendedTrustManager ?: throw SSLPeerUnverifiedException("Platform trust cannot validate this native TLS context")
        val auth=if(session.protocol=="TLSv1.3")"UNKNOWN"else session.cipherSuite.removePrefix("TLS_").substringBefore("_WITH_")
        extended.checkServerTrusted(session.certificates,auth,PublicReadPeerSocket(session))
    }
}

/** Public for platform reflection of stapled OCSP/SCT, with no hidden APIs.
 * Certificates describe an in-progress handshake, not an already trusted peer. */
class PublicReadTlsSession internal constructor(private val host:String,private val port:Int,
    private val tlsProtocol:String,private val cipher:String,internal val certificates:Array<X509Certificate>,
    private val ocsp:ByteArray,private val sct:ByteArray):ExtendedSSLSession() {
    override fun getPeerCertificates():Array<Certificate> = certificates.map {it as Certificate}.toTypedArray()
    override fun getPeerPrincipal():Principal = certificates.first().subjectX500Principal
    override fun getLocalCertificates():Array<Certificate>?=null
    override fun getLocalPrincipal():Principal?=null
    override fun getPeerHost()=host
    override fun getPeerPort()=port
    override fun getCipherSuite()=cipher
    override fun getProtocol()=tlsProtocol
    override fun getId()=ByteArray(0)
    override fun getCreationTime()=0L
    override fun getLastAccessedTime()=0L
    override fun getSessionContext():SSLSessionContext?=null
    override fun isValid()=true
    override fun invalidate()=Unit
    override fun getPacketBufferSize()=32768
    override fun getApplicationBufferSize()=32768
    override fun getValue(name:String):Any?=null
    override fun getValueNames()=emptyArray<String>()
    override fun putValue(name:String,value:Any)=throw UnsupportedOperationException("Read-only handshake evidence")
    override fun removeValue(name:String)=Unit
    @Suppress("DEPRECATION","OVERRIDE_DEPRECATION")
    override fun getPeerCertificateChain():Array<javax.security.cert.X509Certificate> = throw SSLPeerUnverifiedException("Use X.509 certificates")
    override fun getLocalSupportedSignatureAlgorithms()=arrayOf("SHA256withECDSA","SHA384withECDSA","RSASSA-PSS","SHA256withRSA","SHA384withRSA","SHA512withRSA")
    override fun getPeerSupportedSignatureAlgorithms()=emptyArray<String>()
    override fun getRequestedServerNames():List<SNIServerName> = runCatching {listOf<SNIServerName>(SNIHostName(host))}.getOrDefault(emptyList())
    override fun getStatusResponses():List<ByteArray> = if(ocsp.isEmpty())emptyList()else listOf(ocsp.clone())
    fun getPeerSignedCertificateTimestamp():ByteArray?=sct.takeIf {it.isNotEmpty()}?.clone()
}

/** No connect, DNS or stream operations. This carries the native handshake to
 * X509ExtendedTrustManager, preserving the platform's HTTPS/CT/staple policy. */
private class PublicReadPeerSocket(private val peer:PublicReadTlsSession):SSLSocket() {
    override fun getSession():SSLSession=peer
    override fun getHandshakeSession():SSLSession=peer
    override fun getSSLParameters()=SSLParameters(arrayOf(peer.cipherSuite),arrayOf(peer.protocol)).apply {endpointIdentificationAlgorithm="HTTPS"}
    override fun getSupportedCipherSuites()=arrayOf(peer.cipherSuite)
    override fun getEnabledCipherSuites()=supportedCipherSuites
    override fun setEnabledCipherSuites(suites:Array<String>)=throw UnsupportedOperationException()
    override fun getSupportedProtocols()=arrayOf(peer.protocol)
    override fun getEnabledProtocols()=supportedProtocols
    override fun setEnabledProtocols(protocols:Array<String>)=throw UnsupportedOperationException()
    override fun addHandshakeCompletedListener(listener:HandshakeCompletedListener)=throw UnsupportedOperationException()
    override fun removeHandshakeCompletedListener(listener:HandshakeCompletedListener)=throw UnsupportedOperationException()
    override fun startHandshake()=throw UnsupportedOperationException()
    override fun setUseClientMode(mode:Boolean)=throw UnsupportedOperationException()
    override fun getUseClientMode()=true
    override fun setNeedClientAuth(need:Boolean)=throw UnsupportedOperationException()
    override fun getNeedClientAuth()=false
    override fun setWantClientAuth(want:Boolean)=throw UnsupportedOperationException()
    override fun getWantClientAuth()=false
    override fun setEnableSessionCreation(flag:Boolean)=throw UnsupportedOperationException()
    override fun getEnableSessionCreation()=false
}
