#include <assert.h>
#include <stdint.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#include "hev-mapped-dns.h"

/* Exercise the real DNS packet parser and address-to-domain lookup. */
static int
resolve (const char *name)
{
    uint8_t query[512] = { 0x12, 0x34, 0x01, 0, 0, 1 };
    uint8_t response[512];
    const char *label = name;
    int offset = 12;
    int length;
    uint32_t ip;

    while (*label) {
        const char *dot = strchr (label, '.');
        size_t size = dot ? (size_t)(dot - label) : strlen (label);
        assert (size > 0 && size <= 63);
        assert (offset + size + 6 < sizeof (query));
        query[offset++] = size;
        memcpy (&query[offset], label, size);
        offset += size;
        if (!dot)
            break;
        label = dot + 1;
    }
    query[offset++] = 0;
    query[offset++] = 0;
    query[offset++] = 1; /* A */
    query[offset++] = 0;
    query[offset++] = 1; /* IN */

    length = hev_mapped_dns_handle (hev_mapped_dns_get (), query, offset,
                                    response, sizeof (response));
    assert (length == offset + 16);
    assert (response[7] == 1);
    ip = ((uint32_t)response[length - 4] << 24) |
         ((uint32_t)response[length - 3] << 16) |
         ((uint32_t)response[length - 2] << 8) | response[length - 1];
    return (int)ip;
}

static void
start_dns (void)
{
    assert (hev_mapped_dns_init (0x64400000, 0xffc00000, 10000) == 0);
    assert (hev_mapped_dns_get ());
}

static void
stop_dns (void)
{
    hev_mapped_dns_fini ();
    assert (!hev_mapped_dns_get ());
}

static void *
cache_video_address (void *arg)
{
    int *video_ip = arg;
    start_dns ();
    *video_ip = resolve ("video.example.com");
    stop_dns ();
    return NULL;
}

static void
test_restart (void)
{
    const char *cached_name;
    pthread_t previous_thread;
    int video_ip;
    int other_ip;

    /* Each Android tunnel run uses a new native thread. */
    assert (pthread_create (&previous_thread, NULL, cache_video_address,
                            &video_ip) == 0);
    assert (pthread_join (previous_thread, NULL) == 0);

    start_dns ();
    cached_name = hev_mapped_dns_lookup (hev_mapped_dns_get (), video_ip);
    assert (cached_name && strcmp (cached_name, "video.example.com") == 0);
    other_ip = resolve ("other.example.com");
    cached_name = hev_mapped_dns_lookup (hev_mapped_dns_get (), video_ip);
    printf ("After restart: cached video IP resolves to %s\n",
            cached_name ? cached_name : "(missing)");
    fflush (stdout);
    assert (cached_name && strcmp (cached_name, "video.example.com") == 0);
    assert (other_ip != video_ip);
    assert (resolve ("video.example.com") == video_ip);
    /* A real IP with the same host bits must not become a cached domain. */
    assert (!hev_mapped_dns_lookup (hev_mapped_dns_get (), 0x08000000));
    assert (!hev_mapped_dns_lookup (hev_mapped_dns_get (), 0x647fffff));
    stop_dns ();

    for (int i = 0; i < 100; i++) {
        char name[64];
        start_dns ();
        snprintf (name, sizeof (name), "segment-%d.example.com", i);
        assert (resolve (name) != video_ip);
        assert (resolve ("video.example.com") == video_ip);
        stop_dns ();
    }
}

static void
test_config_change_and_bounded_cache (void)
{
    int first;
    int second;

    assert (hev_mapped_dns_init (0x64400000, 0xffc00000, 2) == 0);
    assert (!hev_mapped_dns_lookup (hev_mapped_dns_get (), 0x64400000));
    first = resolve ("first.example.com");
    second = resolve ("second.example.com");
    assert (resolve ("first.example.com") == first); /* Refresh its LRU age. */
    stop_dns ();
    stop_dns (); /* Failed/duplicate cleanup must be harmless. */

    assert (hev_mapped_dns_init (0x64400000, 0xffc00000, 2) == 0);
    assert (resolve ("third.example.com") == second);
    assert (resolve ("first.example.com") == first);
    assert (hev_mapped_dns_get ()->use == 2);
    stop_dns ();

    assert (hev_mapped_dns_init (0x0a000000, 0xff000000, 2) == 0);
    assert (!hev_mapped_dns_lookup (hev_mapped_dns_get (), first));
    assert (resolve ("first.example.com") == 0x0a000000);
    stop_dns ();
    assert (hev_mapped_dns_init (0x0a000000, 0xff000000, 0) == 0);
    assert (!hev_mapped_dns_get ());
    stop_dns ();
}

int
main (void)
{
    test_restart ();
    test_config_change_and_bounded_cache ();
    puts ("Mapped DNS restart tests passed");
    return 0;
}
