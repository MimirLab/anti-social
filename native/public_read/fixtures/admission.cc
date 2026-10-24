/* anti social, GPL-3.0-or-later. No-network input/lifetime qualification. */
#include "engine.h"
#include <array>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>

static int deny(void *,const char *,const uint8_t *const *,const size_t *,size_t){return 0;}
int main() {
    const AsReadHeader good[]={{"user-agent","fixture"},{"accept-encoding","identity"}};
    const AsReadHeader secret[]={{"authorization","Bearer fixture"}};
    const AsReadHeader cookie[]={{"cookie","private=fixture"}};
    const AsReadHeader duplicate[]={{"user-agent","fixture"},{"user-agent","fixture2"}};
    const AsReadHeader injected[]={{"accept","text/html\r\nCookie: private"}};
    auto create=[&](int cohort,const char *host,unsigned port,const char *path,const AsReadHeader *headers,size_t count,size_t limit,AsReadVerify verify){return as_read_create(cohort,host,port,path,headers,count,limit,verify,nullptr);};
    size_t checks=0;
    auto rejected=[&](AsReadEngine *p){assert(!p);++checks;};
    rejected(create(-1,"reader.invalid",443,"/",good,2,4096,deny));
    rejected(create(2,"reader.invalid",443,"/",good,2,4096,deny));
    rejected(create(0,"reader.invalid",0,"/",good,2,4096,deny));
    rejected(create(0,"reader.invalid",65536,"/",good,2,4096,deny));
    for(const char *s:{"reader.invalid:443","bad.invalid\nCookie:",".invalid","invalid.","evil@reader.invalid","reader.invalid/path"})rejected(create(0,s,443,"/",good,2,4096,deny));
    for(const char *s:{"https://other.invalid/","/a#fragment","/contains space","/back\\slash","/\r\n"})rejected(create(0,"reader.invalid",443,s,good,2,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",secret,1,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",cookie,1,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",duplicate,2,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",injected,1,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",good,2,4096,nullptr));
    rejected(create(0,"reader.invalid",443,"/",good,2,0,deny));
    rejected(create(0,"reader.invalid",443,"/",good,2,4*1024*1024+1,deny));
    rejected(create(0,"reader.invalid",443,"/",nullptr,1,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",good,0,4096,deny));
    rejected(create(0,"reader.invalid",443,"/",good,25,4096,deny));
    std::string oversized(1025,'a');const AsReadHeader huge[]={{"user-agent",oversized.c_str()}};
    rejected(create(0,"reader.invalid",443,"/",huge,1,4096,deny));
    assert(as_read_step(nullptr)==AS_READ_FAILED);assert(as_read_header_count(nullptr)==0);assert(as_read_status(nullptr)==0);as_read_destroy(nullptr);checks+=4;
    std::array<uint8_t,32768> bytes{};
    for(int cohort=0;cohort<2;++cohort) {
        auto *engine=create(cohort,"reader.invalid",443,"/",good,2,4096,deny);assert(engine);
        assert(as_read_step(engine)==AS_READ_OUTPUT);
        const size_t n=as_read_output(engine,bytes.data(),bytes.size());assert(n>100&&bytes[0]==22&&bytes[5]==1);
        assert(as_read_step(engine)==AS_READ_INPUT);
        assert(as_read_header_count(engine)==0&&as_read_status(engine)==0);
        size_t body=17;assert(as_read_body(engine,&body)==nullptr&&body==0);
        as_read_eof(engine);assert(as_read_step(engine)==AS_READ_FAILED);assert(strlen(as_read_error(engine))>0);as_read_destroy(engine);checks+=7;
        engine=create(cohort,"reader.invalid",443,"/",good,2,4096,deny);assert(engine);
        assert(as_read_feed(engine,bytes.data(),32769)==AS_READ_FAILED&&as_read_step(engine)==AS_READ_FAILED);as_read_destroy(engine);++checks;
    }
    // Repeated independent malformed TLS records exercise allocate/feed/reject/
    // destroy without network access. No successful partial response is exposed.
    for(unsigned i=0;i<200;++i) {
        auto *engine=create(i%2,"reader.invalid",443,"/",good,2,4096,deny);assert(engine);
        as_read_step(engine);while(as_read_output(engine,bytes.data(),bytes.size())){}
        for(unsigned n=0;n<32;++n)bytes[n]=static_cast<uint8_t>(i*17+n*23);
        as_read_feed(engine,bytes.data(),32);as_read_step(engine);as_read_eof(engine);
        assert(as_read_step(engine)==AS_READ_FAILED&&as_read_header_count(engine)==0&&as_read_status(engine)==0);
        as_read_destroy(engine);++checks;
    }
    printf("{\"checks\":%zu,\"passed\":%zu,\"network\":false}\n",checks,checks);
}
