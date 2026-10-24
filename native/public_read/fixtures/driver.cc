/* anti social, GPL-3.0-or-later. Development-only owned-loopback fixture driver.
 * Socket/file/trust operations here are NOT part of the byte engine or app.
 * The caller owns its fixture CA, port and timeout. Never use for public reads. */
#include "engine.h"
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/sha.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

struct Trust {bssl::UniquePtr<X509_STORE> roots;int calls=0;};
static int verify(void *owner,const char *host,const uint8_t *const *chain,const size_t *sizes,size_t count) {
    auto *trust=static_cast<Trust *>(owner);++trust->calls;
    auto certificates=bssl::UniquePtr<STACK_OF(X509)>(sk_X509_new_null());
    if(!certificates)return 0;
    for(size_t i=0;i<count;++i) {
        const uint8_t *p=chain[i];bssl::UniquePtr<X509> c(d2i_X509(nullptr,&p,sizes[i]));
        if(!c||p!=chain[i]+sizes[i]||!sk_X509_push(certificates.get(),c.get()))return 0;
        c.release();
    }
    bssl::UniquePtr<X509_STORE_CTX> context(X509_STORE_CTX_new());
    if(!context||!X509_STORE_CTX_init(context.get(),trust->roots.get(),sk_X509_value(certificates.get(),0),certificates.get()))return 0;
    auto *params=X509_STORE_CTX_get0_param(context.get());
    X509_VERIFY_PARAM_set_hostflags(params,X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
    if(!X509_VERIFY_PARAM_set_purpose(params,X509_PURPOSE_SSL_SERVER)||!X509_VERIFY_PARAM_set1_host(params,host,strlen(host)))return 0;
    return X509_verify_cert(context.get())==1;
}
int main(int argc,char **argv) {
    if(argc!=7)return 2;
    const int port=atoi(argv[1]),cohort=atoi(argv[2]);const char *host=argv[3],*ca=argv[4];
    const int fragment=atoi(argv[5]);const size_t limit=strtoul(argv[6],nullptr,10);
    if(port<1||port>65535||fragment<1||fragment>32768)return 2;
    Trust trust;trust.roots.reset(X509_STORE_new());bssl::UniquePtr<BIO> file(BIO_new_file(ca,"r"));
    bssl::UniquePtr<X509> root(file?PEM_read_bio_X509(file.get(),nullptr,nullptr,nullptr):nullptr);
    if(!trust.roots||!root||!X509_STORE_add_cert(trust.roots.get(),root.get()))return 3;
    // These are fixture headers; production must supply its checked cohort from
    // PublicPersonaFactory. No generator or library default supplies headers.
    const std::string ua=cohort==0?
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36":
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";
    const AsReadHeader headers[]={{"user-agent",ua.c_str()},{"accept","text/html"},{"accept-encoding","gzip, deflate, br, zstd"},{"accept-language","en-US,en;q=0.9"}};
    auto engine=std::unique_ptr<AsReadEngine,decltype(&as_read_destroy)>(as_read_create(cohort,host,port,"/public-fixture",headers,4,limit,verify,&trust),as_read_destroy);
    if(!engine){puts("{\"created\":false}");return 0;}
    const int socket_fd=socket(AF_INET,SOCK_STREAM,0);if(socket_fd<0)return 4;
    const timeval timeout{3,0};setsockopt(socket_fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(socket_fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    sockaddr_in address{};address.sin_family=AF_INET;address.sin_port=htons(port);address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if(connect(socket_fd,reinterpret_cast<sockaddr *>(&address),sizeof(address))){close(socket_fd);return 5;}
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(8);int state=0;
    std::array<uint8_t,32768> buffer{};
    for(size_t turn=0;turn<200000;++turn) {
        state=as_read_step(engine.get());if(state==AS_READ_FAILED||state==AS_READ_DONE)break;
        size_t n=0;
        while((n=as_read_output(engine.get(),buffer.data(),buffer.size()))) {
            size_t at=0;
            while(at<n){const ssize_t sent=send(socket_fd,buffer.data()+at,n-at,MSG_NOSIGNAL);if(sent<=0){as_read_eof(engine.get());break;}at+=sent;}
        }
        if(std::chrono::steady_clock::now()>=deadline){as_read_eof(engine.get());state=AS_READ_FAILED;break;}
        if(state==AS_READ_OUTPUT)continue;
        const ssize_t got=recv(socket_fd,buffer.data(),fragment,0);
        if(got<=0){as_read_eof(engine.get());state=AS_READ_FAILED;break;}
        if(as_read_feed(engine.get(),buffer.data(),got)==AS_READ_FAILED){state=AS_READ_FAILED;break;}
    }
    close(socket_fd);size_t n=0;const uint8_t *body=as_read_body(engine.get(),&n);uint8_t digest[32];char hex[65];
    SHA256(body,n,digest);for(size_t i=0;i<32;++i)snprintf(hex+i*2,3,"%02x",digest[i]);
    printf("{\"created\":true,\"state\":%d,\"status\":%d,\"bodyBytes\":%zu,\"bodySha256\":\"%s\",\"headers\":%zu,\"verifyCalls\":%d,\"error\":\"%s\"}\n",state,as_read_status(engine.get()),n,hex,as_read_header_count(engine.get()),trust.calls,as_read_error(engine.get()));
    return 0;
}
