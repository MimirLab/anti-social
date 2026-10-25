/* anti social, GPL-3.0-or-later. First-party bounded single-hop orchestration.
 * Crypto/TLS: pinned BoringSSL; HTTP/2: nghttp2; decoding: zlib/Brotli/Zstandard.
 * See sources.json and README.md. No libcurl API or operating-system transport. */
#include "engine.h"
#include <openssl/ssl.h>
#include <openssl/bio.h>
#include <openssl/pool.h>
#include <openssl/aead.h>
#include <openssl/crypto.h>
#include <nghttp2/nghttp2.h>
#include <brotli/decode.h>
#include <zlib.h>
#include <zstd.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

namespace {
constexpr size_t CHUNK=32768, HEADER_LIMIT=32768, CERT_LIMIT=262144;
constexpr size_t MAX_DECODED=4*1024*1024, MAX_WIRE=8*1024*1024;
bool ascii(const char *s,size_t limit,bool name=false) {
    if(!s)return false;
    const size_t n=strnlen(s,limit+1);
    if(!n||n>limit)return false;
    for(size_t i=0;i<n;++i) {
        const unsigned char c=s[i];
        if(name?!(c>='a'&&c<='z')&&!(c>='0'&&c<='9')&&c!='-':c<32||c>126)return false;
    }
    return true;
}
bool host_valid(const char *host) {
    if(!ascii(host,253))return false;
    for(const char *p=host;*p;++p)if(!((*p>='a'&&*p<='z')||(*p>='0'&&*p<='9')||*p=='.'||*p=='-'))return false;
    return host[0]!='.'&&host[strlen(host)-1]!='.';
}
bool allowed_header(const char *name) {
    static const char *allowed[]={"user-agent","accept","accept-language","accept-encoding",
        "upgrade-insecure-requests","sec-ch-ua","sec-ch-ua-mobile","sec-ch-ua-platform",
        "sec-fetch-site","sec-fetch-mode","sec-fetch-user","sec-fetch-dest","cookie"};
    for(const char *s:allowed)if(!strcmp(name,s))return true;
    return false;
}
int decompress_cert(SSL *,CRYPTO_BUFFER **out,size_t length,const uint8_t *in,size_t n) {
    if(!length||length>CERT_LIMIT||n>CERT_LIMIT)return 0;
    std::vector<uint8_t> bytes(length);size_t got=length;
    if(BrotliDecoderDecompress(n,in,&got,bytes.data())!=BROTLI_DECODER_RESULT_SUCCESS||got!=length)return 0;
    *out=CRYPTO_BUFFER_new(bytes.data(),length,nullptr);return *out!=nullptr;
}
struct Decoder {
    enum Kind {IDENTITY,GZIP,DEFLATE,BR,ZSTD}kind=IDENTITY;
    z_stream z{};BrotliDecoderState *br=nullptr;ZSTD_DStream *zs=nullptr;
    bool initialized=false,ended=false;size_t encoded=0;
    ~Decoder(){if(initialized&&(kind==GZIP||kind==DEFLATE))inflateEnd(&z);if(br)BrotliDecoderDestroyInstance(br);if(zs)ZSTD_freeDStream(zs);}
    bool start(const std::string &value) {
        if(initialized)return false;
        if(value.empty()||value=="identity")kind=IDENTITY;
        else if(value=="gzip")kind=GZIP;else if(value=="deflate")kind=DEFLATE;
        else if(value=="br")kind=BR;else if(value=="zstd")kind=ZSTD;else return false;
        if(kind==GZIP||kind==DEFLATE){if(inflateInit2(&z,kind==GZIP?31:15)!=Z_OK)return false;}
        if(kind==BR){br=BrotliDecoderCreateInstance(nullptr,nullptr,nullptr);if(!br)return false;}
        if(kind==ZSTD){zs=ZSTD_createDStream();if(!zs||ZSTD_isError(ZSTD_initDStream(zs))||ZSTD_isError(ZSTD_DCtx_setParameter(zs,ZSTD_d_windowLogMax,23)))return false;}
        initialized=true;return true;
    }
    bool receive(const uint8_t *in,size_t n,std::vector<uint8_t> &body,size_t limit,AsReadDecoded account,void *owner) {
        if(!initialized||n>MAX_WIRE-encoded||(ended&&n))return false;
        if(!n)return true;
        encoded+=n;
        if(kind==IDENTITY){if(n>limit-body.size()||(account&&!account(owner,n)))return false;body.insert(body.end(),in,in+n);return true;}
        std::array<uint8_t,CHUNK> out{};size_t remaining=n;const uint8_t *next=in;
        // Each turn must consume input, produce output, or terminate. This also
        // prevents an inconsistent decoder result from turning into a spin.
        for(size_t turn=0;turn<MAX_DECODED/CHUNK+CHUNK;++turn) {
            size_t got=0,used=0;bool more=false;
            if(kind==GZIP||kind==DEFLATE) {
                z.next_in=const_cast<Bytef *>(next);z.avail_in=static_cast<uInt>(remaining);z.next_out=out.data();z.avail_out=out.size();
                const int status=inflate(&z,Z_NO_FLUSH);used=remaining-z.avail_in;got=out.size()-z.avail_out;
                if(status==Z_STREAM_END)ended=true;else if(status!=Z_OK&&status!=Z_BUF_ERROR)return false;
                more=z.avail_out==0;
            }else if(kind==BR) {
                size_t available_out=out.size();uint8_t *dest=out.data();size_t available_in=remaining;
                const uint8_t *source=next;
                const auto status=BrotliDecoderDecompressStream(br,&available_in,&source,&available_out,&dest,nullptr);
                used=remaining-available_in;got=out.size()-available_out;
                if(status==BROTLI_DECODER_RESULT_SUCCESS)ended=true;else if(status==BROTLI_DECODER_RESULT_ERROR)return false;
                more=status==BROTLI_DECODER_RESULT_NEEDS_MORE_OUTPUT;
            }else {
                ZSTD_inBuffer input{next,remaining,0};ZSTD_outBuffer output{out.data(),out.size(),0};
                const size_t status=ZSTD_decompressStream(zs,&output,&input);
                if(ZSTD_isError(status))return false;used=input.pos;got=output.pos;ended=status==0;more=got==out.size();
            }
            if(got>limit-body.size()||(got&&account&&!account(owner,got)))return false;
            body.insert(body.end(),out.data(),out.data()+got);next+=used;remaining-=used;
            if(ended)return remaining==0;
            if(!remaining&&!more)return true;
            if(!used&&!got)return remaining==0;
        }
        return false;
    }
    bool finish()const {return initialized&&(kind==IDENTITY||ended);}
};
}

struct AsReadEngine {
    bssl::UniquePtr<SSL_CTX> context;bssl::UniquePtr<SSL> tls;
    BIO *input=nullptr,*output=nullptr;nghttp2_session *h2=nullptr;
    AsReadVerify verify=nullptr;AsReadDecoded account=nullptr;void *owner=nullptr;
    std::string host,path,authority,error,encoding;
    std::vector<std::pair<std::string,std::string>> request_headers,response_headers;
    std::vector<uint8_t> body,pending;size_t pending_at=0,header_bytes=0,wire_bytes=0,frame_count=0,limit=0;
    Decoder decoder;int status=0,block_status=0,blocks=0;bool trusted=false,started=false,headers_done=false,done=false,encoding_seen=false;
    ~AsReadEngine(){if(h2)nghttp2_session_del(h2);}
    int fail(const char *message){if(error.empty())error=message;return AS_READ_FAILED;}
    static ssl_verify_result_t verify_peer(SSL *ssl,uint8_t *alert) {
        auto *e=static_cast<AsReadEngine *>(SSL_get_app_data(ssl));
        const auto *certs=SSL_get0_peer_certificates(ssl);const size_t count=certs?sk_CRYPTO_BUFFER_num(certs):0;
        if(!e||!e->verify||count==0||count>16){*alert=SSL_AD_BAD_CERTIFICATE;return ssl_verify_invalid;}
        std::array<const uint8_t *,16> data{};std::array<size_t,16> sizes{};size_t total=0;
        for(size_t i=0;i<count;++i){const CRYPTO_BUFFER *b=sk_CRYPTO_BUFFER_value(certs,i);sizes[i]=CRYPTO_BUFFER_len(b);if(sizes[i]>CERT_LIMIT-total){*alert=SSL_AD_BAD_CERTIFICATE;return ssl_verify_invalid;}total+=sizes[i];data[i]=CRYPTO_BUFFER_data(b);}
        e->trusted=e->verify(e->owner,e->host.c_str(),data.data(),sizes.data(),count)==1;
        if(!e->trusted){*alert=SSL_AD_CERTIFICATE_UNKNOWN;return ssl_verify_invalid;}return ssl_verify_ok;
    }
    static int begin(nghttp2_session *,const nghttp2_frame *frame,void *user) {
        auto *e=static_cast<AsReadEngine *>(user);
        if(frame->hd.type==NGHTTP2_HEADERS){
            if(frame->hd.stream_id!=1||++e->blocks>12)return e->fail("Invalid response header blocks");
            e->block_status=0;
        }
        return 0;
    }
    static int header(nghttp2_session *,const nghttp2_frame *frame,const uint8_t *name,size_t nl,const uint8_t *value,size_t vl,uint8_t,void *user) {
        auto *e=static_cast<AsReadEngine *>(user);
        if(frame->hd.stream_id!=1||nl>256||vl>8192||nl+vl+32>HEADER_LIMIT-e->header_bytes||e->response_headers.size()>=128)return e->fail("Response headers exceed limit");
        e->header_bytes+=nl+vl+32;
        std::string n(reinterpret_cast<const char *>(name),nl),v(reinterpret_cast<const char *>(value),vl);
        if(n==":status") {
            if(e->headers_done||e->block_status||v.size()!=3||v[0]<'1'||v[0]>'5'||v[1]<'0'||v[1]>'9'||v[2]<'0'||v[2]>'9')return e->fail("Invalid response status");
            e->block_status=(v[0]-'0')*100+(v[1]-'0')*10+v[2]-'0';
        }else {
            if(n.empty()||n[0]==':'||v.find('\0')!=v.npos||v.find('\r')!=v.npos||v.find('\n')!=v.npos)return e->fail("Invalid response header");
            if(n=="content-encoding"){
                if(e->headers_done||e->encoding_seen)return e->fail("Ambiguous response encoding");
                e->encoding_seen=true;
                const size_t first=v.find_first_not_of(" \t"),last=v.find_last_not_of(" \t");
                if(first==v.npos)return e->fail("Empty response encoding");
                e->encoding=v.substr(first,last-first+1);
                for(char &c:e->encoding)if(c>='A'&&c<='Z')c+=('a'-'A');
            }
            // Trailer fields never become a new authority for navigation,
            // cookies, decoding or source identity. Keep regular headers only.
            if(!e->headers_done)e->response_headers.emplace_back(n,v);
        }
        return 0;
    }
    static int frame(nghttp2_session *,const nghttp2_frame *frame,void *user) {
        auto *e=static_cast<AsReadEngine *>(user);
        if(++e->frame_count>4096)return e->fail("Response frame limit exceeded");
        if(frame->hd.type==NGHTTP2_PUSH_PROMISE)return e->fail("Unrequested push");
        if(frame->hd.type==NGHTTP2_GOAWAY&&frame->goaway.last_stream_id<1)return e->fail("Server declined the request stream");
        if(frame->hd.type==NGHTTP2_HEADERS&&frame->hd.stream_id==1&&!e->headers_done){
            if(e->block_status<100)return e->fail("Response omitted status");
            if(e->block_status>=200){e->status=e->block_status;e->headers_done=true;if(!e->decoder.start(e->encoding))return e->fail("Unsupported response encoding");}
            else {if(e->block_status==101)return e->fail("Unexpected protocol switch");e->response_headers.clear();e->encoding.clear();e->encoding_seen=false;}
        }
        return 0;
    }
    static int data(nghttp2_session *,uint8_t,int32_t stream,const uint8_t *bytes,size_t n,void *user) {
        auto *e=static_cast<AsReadEngine *>(user);
        if(stream!=1||!e->headers_done||e->status==204||e->status==304||!e->decoder.receive(bytes,n,e->body,e->limit,e->account,e->owner))return e->fail("Invalid or excessive decoded response");
        return 0;
    }
    static int close_stream(nghttp2_session *,int32_t stream,uint32_t code,void *user) {
        auto *e=static_cast<AsReadEngine *>(user);
        if(stream!=1||code!=NGHTTP2_NO_ERROR||!e->headers_done||
            (e->status!=204&&e->status!=304&&!e->decoder.finish()))return e->fail("Incomplete response");
        e->done=true;return 0;
    }
    bool start_http() {
        const uint8_t *alpn=nullptr;unsigned n=0;SSL_get0_alpn_selected(tls.get(),&alpn,&n);
        if(!trusted||n!=2||memcmp(alpn,"h2",2))return fail("HTTP protocol not supported")>0;
        nghttp2_session_callbacks *callbacks=nullptr;
        if(nghttp2_session_callbacks_new(&callbacks))return false;
        nghttp2_session_callbacks_set_on_begin_headers_callback(callbacks,begin);
        nghttp2_session_callbacks_set_on_header_callback(callbacks,header);
        nghttp2_session_callbacks_set_on_frame_recv_callback(callbacks,frame);
        nghttp2_session_callbacks_set_on_data_chunk_recv_callback(callbacks,data);
        nghttp2_session_callbacks_set_on_stream_close_callback(callbacks,close_stream);
        const int result=nghttp2_session_client_new(&h2,callbacks,this);nghttp2_session_callbacks_del(callbacks);
        if(result)return false;
        const nghttp2_settings_entry settings[]={{NGHTTP2_SETTINGS_HEADER_TABLE_SIZE,65536},{NGHTTP2_SETTINGS_ENABLE_PUSH,0},{NGHTTP2_SETTINGS_INITIAL_WINDOW_SIZE,6291456},{NGHTTP2_SETTINGS_MAX_HEADER_LIST_SIZE,262144}};
        if(nghttp2_submit_settings(h2,NGHTTP2_FLAG_NONE,settings,4)||nghttp2_submit_window_update(h2,NGHTTP2_FLAG_NONE,0,15663105))return false;
        auto all=request_headers;all.insert(all.begin(),{{":method","GET"},{":authority",authority},{":scheme","https"},{":path",path}});
        std::vector<nghttp2_nv> nv;
        for(auto &v:all)nv.push_back({reinterpret_cast<uint8_t *>(v.first.data()),reinterpret_cast<uint8_t *>(v.second.data()),v.first.size(),v.second.size(),NGHTTP2_NV_FLAG_NONE});
        if(nghttp2_submit_request(h2,nullptr,nv.data(),nv.size(),nullptr,nullptr)!=1)return false;
        started=true;return true;
    }
    bool flush_http() {
        for(int i=0;i<64;++i) {
            if(pending_at==pending.size()) {
                pending.clear();pending_at=0;const uint8_t *next=nullptr;
                const auto n=nghttp2_session_mem_send2(h2,&next);
                if(n<0)return false;if(!n)return true;
                if(static_cast<size_t>(n)>HEADER_LIMIT)return false;
                pending.assign(next,next+n);
            }
            // Memory BIO output is deliberately drained by the owner. Never
            // allocate an unbounded TLS write queue under a slow connection.
            if(BIO_ctrl_pending(output)>2*CHUNK)return true;
            const int n=SSL_write(tls.get(),pending.data()+pending_at,static_cast<int>(pending.size()-pending_at));
            if(n<=0){const int error=SSL_get_error(tls.get(),n);return error==SSL_ERROR_WANT_READ||error==SSL_ERROR_WANT_WRITE;}
            pending_at+=n;
        }
        return fail("Outgoing control frame limit exceeded")>0;
    }
};

extern "C" unsigned as_read_supported_cohorts(void) {
    // Both initial observed cohorts prefer AES-GCM before ChaCha20. Upstream
    // BoringSSL chooses TLS1.3 ordering from real CPU support. Admit only a
    // matching cohort; never patch crypto or use its testing override API.
    return EVP_has_aes_hardware()?3u:0u;
}
extern "C" const char *as_read_versions(void) {
    static const std::string versions=std::string("{\"boringssl\":\"")+AS_READ_BORINGSSL_REVISION+
        "\",\"nghttp2\":\""+nghttp2_version(0)->version_str+"\",\"zlib\":\""+zlibVersion()+
        "\",\"brotli\":"+std::to_string(BrotliDecoderVersion())+",\"zstd\":\""+ZSTD_versionString()+"\"}";
    return versions.c_str();
}
extern "C" int as_read_account_decoded(AsReadEngine *e,AsReadDecoded account) {
    if(!e||e->trusted||e->started)return 0;e->account=account;return 1;
}
extern "C" const char *as_read_peer_protocol(const AsReadEngine *e){return e?SSL_get_version(e->tls.get()):"";}
extern "C" const char *as_read_peer_cipher(const AsReadEngine *e){const SSL_CIPHER *c=e?SSL_get_current_cipher(e->tls.get()):nullptr;return c?SSL_CIPHER_standard_name(c):"";}
extern "C" const uint8_t *as_read_peer_ocsp(const AsReadEngine *e,size_t *n){const uint8_t *p=nullptr;*n=0;if(e)SSL_get0_ocsp_response(e->tls.get(),&p,n);return p;}
extern "C" const uint8_t *as_read_peer_sct(const AsReadEngine *e,size_t *n){const uint8_t *p=nullptr;*n=0;if(e)SSL_get0_signed_cert_timestamp_list(e->tls.get(),&p,n);return p;}
extern "C" AsReadEngine *as_read_create(int cohort,const char *host,unsigned port,const char *path,const AsReadHeader *headers,size_t count,size_t limit,AsReadVerify verify,void *owner) {
    if(cohort<0||cohort>1||!host_valid(host)||port<1||port>65535||!ascii(path,8192)||path[0]!='/'||strchr(path,'#')||strchr(path,' ')||strchr(path,'\\')||!verify||!headers||count<1||count>24||!limit||limit>MAX_DECODED)return nullptr;
    if(!(as_read_supported_cohorts()&(1u<<cohort)))return nullptr;
    auto e=std::unique_ptr<AsReadEngine>(new(std::nothrow)AsReadEngine());if(!e)return nullptr;
    e->host=host;e->path=path;e->authority=host;if(port!=443)e->authority+=":"+std::to_string(port);e->verify=verify;e->owner=owner;e->limit=limit;
    size_t header_bytes=0;
    for(size_t i=0;i<count;++i) {
        const auto &h=headers[i];if(!ascii(h.name,64,true)||!allowed_header(h.name)||!ascii(h.value,!strcmp(h.name,"cookie")?16384:1024))return nullptr;
        if(std::any_of(e->request_headers.begin(),e->request_headers.end(),[&](const auto &v){return v.first==h.name;}))return nullptr;
        header_bytes+=strlen(h.name)+strlen(h.value);if(header_bytes>24576)return nullptr;
        e->request_headers.emplace_back(h.name,h.value);
    }
    e->context.reset(SSL_CTX_new(TLS_client_method()));if(!e->context)return nullptr;
    const uint16_t sigalgs[]={0x0403,0x0804,0x0401,0x0503,0x0805,0x0501,0x0806,0x0601};
    const uint16_t mobile_groups[]={29,23,24},desktop_groups[]={4588,29,23,24};
    if(!SSL_CTX_set_min_proto_version(e->context.get(),TLS1_2_VERSION)||
       !SSL_CTX_set_max_proto_version(e->context.get(),TLS1_3_VERSION)||
       !SSL_CTX_set_strict_cipher_list(e->context.get(),"ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-RSA-AES128-SHA:ECDHE-RSA-AES256-SHA:AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA:AES256-SHA")||
       !SSL_CTX_set1_group_ids(e->context.get(),cohort==0?mobile_groups:desktop_groups,cohort==0?3:4)||
       !SSL_CTX_set_verify_algorithm_prefs(e->context.get(),sigalgs,8)||
       !SSL_CTX_add_cert_compression_alg(e->context.get(),2,nullptr,decompress_cert))return nullptr;
    SSL_CTX_set_grease_enabled(e->context.get(),1);SSL_CTX_set_permute_extensions(e->context.get(),1);
    SSL_CTX_enable_signed_cert_timestamps(e->context.get());SSL_CTX_enable_ocsp_stapling(e->context.get());
    SSL_CTX_set_max_cert_list(e->context.get(),CERT_LIMIT);
    SSL_CTX_set_custom_verify(e->context.get(),SSL_VERIFY_PEER,AsReadEngine::verify_peer);
    e->tls.reset(SSL_new(e->context.get()));if(!e->tls)return nullptr;
    SSL_set_app_data(e->tls.get(),e.get());SSL_set_connect_state(e->tls.get());
    const uint8_t alpn[]={2,'h','2',8,'h','t','t','p','/','1','.','1'};
    if(!SSL_set_tlsext_host_name(e->tls.get(),host)||SSL_set_alpn_protos(e->tls.get(),alpn,sizeof(alpn))||!SSL_add_application_settings(e->tls.get(),reinterpret_cast<const uint8_t *>("h2"),2,nullptr,0))return nullptr;
    SSL_set_alps_use_new_codepoint(e->tls.get(),cohort==1);SSL_set_enable_ech_grease(e->tls.get(),1);
    e->input=BIO_new(BIO_s_mem());e->output=BIO_new(BIO_s_mem());
    if(!e->input||!e->output){BIO_free(e->input);BIO_free(e->output);return nullptr;}
    BIO_set_mem_eof_return(e->input,-1);BIO_set_mem_eof_return(e->output,-1);
    SSL_set_bio(e->tls.get(),e->input,e->output);return e.release();
}
extern "C" int as_read_feed(AsReadEngine *e,const uint8_t *data,size_t n) {
    if(!e||!e->error.empty()||e->done)return AS_READ_FAILED;
    if(!data||!n||n>CHUNK)return e->fail("Invalid input chunk");
    if(BIO_ctrl_pending(e->input)>2*CHUNK||n>MAX_WIRE-e->wire_bytes||
        (!e->headers_done&&e->wire_bytes+n>512*1024))return e->fail("Encrypted input limit exceeded");
    e->wire_bytes+=n;
    if(BIO_write(e->input,data,static_cast<int>(n))!=static_cast<int>(n))return e->fail("TLS input failed");
    return AS_READ_INPUT;
}
extern "C" int as_read_step(AsReadEngine *e) {
    if(!e)return AS_READ_FAILED;if(!e->error.empty())return AS_READ_FAILED;if(e->done)return AS_READ_DONE;
    if(!SSL_is_init_finished(e->tls.get())) {
        const int n=SSL_do_handshake(e->tls.get());
        if(n!=1){const int error=SSL_get_error(e->tls.get(),n);if(error!=SSL_ERROR_WANT_READ&&error!=SSL_ERROR_WANT_WRITE)return e->fail("TLS peer verification or handshake failed");return BIO_ctrl_pending(e->output)?AS_READ_OUTPUT:AS_READ_INPUT;}
    }
    if(!e->started&&!e->start_http())return e->fail("HTTP initialization failed");
    if(!e->flush_http())return e->fail("HTTP output failed");
    std::array<uint8_t,CHUNK> input{};
    for(int turn=0;turn<16&&!e->done;++turn) {
        const int n=SSL_read(e->tls.get(),input.data(),input.size());
        if(n>0) {
            const auto used=nghttp2_session_mem_recv2(e->h2,input.data(),n);
            if(used!=n||!e->error.empty())return e->fail("Invalid HTTP/2 input");
            if(!e->flush_http())return e->fail("HTTP control output failed");
        }else {
            const int error=SSL_get_error(e->tls.get(),n);
            if(error==SSL_ERROR_WANT_READ||error==SSL_ERROR_WANT_WRITE)break;
            return e->fail("TLS ended before complete response");
        }
    }
    if(!e->error.empty())return AS_READ_FAILED;
    return e->done?AS_READ_DONE:BIO_ctrl_pending(e->output)?AS_READ_OUTPUT:AS_READ_INPUT;
}
extern "C" size_t as_read_output(AsReadEngine *e,uint8_t *out,size_t n) {
    if(!e||!out||!n)return 0;const int got=BIO_read(e->output,out,static_cast<int>(std::min(n,CHUNK)));return got>0?got:0;
}
extern "C" void as_read_eof(AsReadEngine *e){if(e&&!e->done)e->fail("Truncated response");}
extern "C" int as_read_status(const AsReadEngine *e){return e&&e->done&&e->error.empty()?e->status:0;}
extern "C" size_t as_read_header_count(const AsReadEngine *e){return e&&e->done&&e->error.empty()?e->response_headers.size():0;}
extern "C" const char *as_read_header_name(const AsReadEngine *e,size_t i){return i<as_read_header_count(e)?e->response_headers[i].first.c_str():nullptr;}
extern "C" const char *as_read_header_value(const AsReadEngine *e,size_t i){return i<as_read_header_count(e)?e->response_headers[i].second.c_str():nullptr;}
extern "C" const uint8_t *as_read_body(const AsReadEngine *e,size_t *n){if(n)*n=e&&e->done&&e->error.empty()?e->body.size():0;return e&&e->done&&e->error.empty()?e->body.data():nullptr;}
extern "C" const char *as_read_error(const AsReadEngine *e){return e?e->error.c_str():"Invalid request";}
extern "C" void as_read_destroy(AsReadEngine *e){delete e;}
