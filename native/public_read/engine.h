/* anti social, GPL-3.0-or-later. Experimental byte-only public-read engine.
 * No sockets, DNS, files, redirects, credentials, persistent sessions or retries.
 * Its owner must enforce Gateway policy and verify the complete peer identity.
 * This is not a public ABI and is not enabled in production builds. */
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct AsReadEngine AsReadEngine;
typedef struct { const char *name; const char *value; } AsReadHeader;
/* Mandatory synchronous verifier. Return 1 only after validating the DER chain
 * AND hostname under the owner's trust policy. Data is borrowed for this call. */
typedef int (*AsReadVerify)(void *owner, const char *host,
    const uint8_t *const *chain, const size_t *sizes, size_t count);
enum { AS_READ_FAILED=-1, AS_READ_INPUT=0, AS_READ_OUTPUT=1, AS_READ_DONE=2 };
/* cohort: 0=Android131, 1=macOS142. One verified HTTPS GET, HTTP/2 only initially.
 * HTTP/1 ALPN is advertised as in the observed cohort but currently fails before
 * sending HTTP. The owner can choose another already-permitted route. */
AsReadEngine *as_read_create(int cohort, const char *host, unsigned port,
    const char *path, const AsReadHeader *headers, size_t header_count,
    size_t decoded_limit, AsReadVerify verify, void *owner);
/* Single-owner calls. Arbitrary fragmentation is accepted. Input/output chunks
 * are <=32 KiB. Pump before adding more input; never busy-poll AS_READ_INPUT. */
int as_read_feed(AsReadEngine *, const uint8_t *, size_t);
int as_read_step(AsReadEngine *);
size_t as_read_output(AsReadEngine *, uint8_t *, size_t);
/* EOF before completed END_STREAM is a failure, including compressed truncation. */
void as_read_eof(AsReadEngine *);
int as_read_status(const AsReadEngine *);
size_t as_read_header_count(const AsReadEngine *);
const char *as_read_header_name(const AsReadEngine *, size_t);
const char *as_read_header_value(const AsReadEngine *, size_t);
const uint8_t *as_read_body(const AsReadEngine *, size_t *size);
const char *as_read_error(const AsReadEngine *);
void as_read_destroy(AsReadEngine *);
#ifdef __cplusplus
}
#endif
