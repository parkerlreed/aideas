/*
 * usb_iso.c — Minimal isochronous USB reader for Android.
 *
 * Android's Java UsbRequest API for isochronous endpoints is unreliable on
 * many OEM devices.  This thin native layer talks directly to the Linux
 * USBDEVFS kernel interface, which always works as long as the app holds
 * a UsbDeviceConnection (i.e. has the file descriptor).
 *
 * Flow:
 *   nativeOpen()      — allocate URB pool, submit all URBs to the kernel
 *   nativeReadFrame() — REAP URBs in a loop, strip UVC headers, accumulate
 *                       YUYV payload until one complete frame is ready
 *   nativeClose()     — discard pending URBs, free memory
 */

#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <unistd.h>

#define TAG  "UsbIso"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Number of URBs in the circular pool. */
#define NUM_URBS 8

typedef struct {
    int      fd;
    int      ep_addr;
    int      max_packet;
    int      packets_per_urb;
    volatile int running;

    struct usbdevfs_urb *urbs[NUM_URBS];
    uint8_t             *bufs[NUM_URBS];
} IsoCtx;

/* (Re-)submit one URB from the pool. */
static int submit_urb(IsoCtx *ctx, int idx)
{
    struct usbdevfs_urb *urb = ctx->urbs[idx];
    urb->status        = 0;
    urb->actual_length = 0;
    for (int p = 0; p < urb->number_of_packets; p++) {
        urb->iso_frame_desc[p].actual_length = 0;
        urb->iso_frame_desc[p].status        = 0;
    }
    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb);
    if (ret < 0)
        LOGW("SUBMITURB[%d]: %s", idx, strerror(errno));
    return ret;
}

/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
Java_com_example_thermalviewer_UsbIsoReader_nativeOpen(
        JNIEnv *env, jclass clazz,
        jint fd, jint ep_addr, jint max_packet)
{
    IsoCtx *ctx = (IsoCtx *)calloc(1, sizeof(IsoCtx));
    if (!ctx) return 0;

    ctx->fd         = fd;
    ctx->ep_addr    = ep_addr;
    ctx->max_packet = max_packet;
    ctx->running    = 1;

    /* Linux caps isochronous URB buffer_length at MAX_ISO_BUFFER_LENGTH
     * (typically 16 KB or 32 KB depending on kernel config).  Stay well
     * under that by targeting 16 KB per URB. */
    int ppurb = 16384 / max_packet;
    if (ppurb < 1)  ppurb = 1;
    if (ppurb > 64) ppurb = 64;
    ctx->packets_per_urb = ppurb;

    int buf_size = max_packet * ppurb;
    LOGI("Opening: ep=0x%02x maxPkt=%d ppurb=%d bufSize=%d",
         ep_addr, max_packet, ppurb, buf_size);

    for (int i = 0; i < NUM_URBS; i++) {
        size_t urb_sz = sizeof(struct usbdevfs_urb)
                      + ppurb * sizeof(struct usbdevfs_iso_packet_desc);
        ctx->urbs[i] = (struct usbdevfs_urb *)calloc(1, urb_sz);
        ctx->bufs[i] = (uint8_t *)malloc(buf_size);
        if (!ctx->urbs[i] || !ctx->bufs[i]) {
            LOGE("alloc failed at URB %d", i);
            /* nativeClose will free whatever was allocated. */
            return (jlong)(uintptr_t)ctx;
        }

        struct usbdevfs_urb *urb = ctx->urbs[i];
        urb->type             = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint         = (unsigned char)ep_addr;
        urb->buffer           = ctx->bufs[i];
        urb->buffer_length    = buf_size;
        urb->number_of_packets = ppurb;
        urb->flags            = USBDEVFS_URB_ISO_ASAP;
        urb->usercontext      = (void *)(uintptr_t)i;
        for (int p = 0; p < ppurb; p++)
            urb->iso_frame_desc[p].length = max_packet;

        if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0)
            LOGE("Initial SUBMITURB[%d]: %s", i, strerror(errno));
        else
            LOGI("Submitted URB %d", i);
    }
    return (jlong)(uintptr_t)ctx;
}

/* ------------------------------------------------------------------ */
/*
 * Read exactly one complete YUYV frame into frame_buf.
 *
 * UVC isochronous payload header (bytes 0..bHeaderLength-1):
 *   byte 0 : bHeaderLength  (typically 12)
 *   byte 1 : bmHeaderInfo
 *              bit 0 = FID   frame-ID toggle — changes on every new frame
 *              bit 1 = EOF   end-of-frame marker
 *              bit 6 = ERR   error in payload
 *
 * Returns number of bytes written (== frame_size on success),
 *         -1 on bad args, -2 on device disconnected.
 */
JNIEXPORT jint JNICALL
Java_com_example_thermalviewer_UsbIsoReader_nativeReadFrame(
        JNIEnv *env, jclass clazz,
        jlong handle, jbyteArray frame_buf, jint frame_size)
{
    IsoCtx *ctx = (IsoCtx *)(uintptr_t)handle;
    if (!ctx || !ctx->running) return -1;

    jbyte *frame = (*env)->GetByteArrayElements(env, frame_buf, NULL);
    if (!frame) return -1;

    int frame_pos = 0;
    int last_fid  = -1;

    while (ctx->running) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
            if (errno == EAGAIN) {
                usleep(1000);
                continue;
            }
            if (errno == ENODEV || errno == ESHUTDOWN) {
                (*env)->ReleaseByteArrayElements(env, frame_buf, frame, 0);
                return -2;
            }
            usleep(5000);
            continue;
        }
        if (!urb) { usleep(1000); continue; }

        int      idx  = (int)(uintptr_t)urb->usercontext;
        uint8_t *data = ctx->bufs[idx];
        int      off  = 0;
        int      done = 0;

        for (int p = 0; p < urb->number_of_packets && !done; p++) {
            int actual  = urb->iso_frame_desc[p].actual_length;
            int pstatus = urb->iso_frame_desc[p].status;

            if (actual < 2 || pstatus != 0) {
                off += ctx->max_packet;
                continue;
            }

            int     hlen = data[off];
            uint8_t info = data[off + 1];
            int     fid  = info & 0x01;
            int     eof  = (info >> 1) & 0x01;
            int     err  = (info >> 6) & 0x01;

            if (!err && hlen >= 2 && actual > hlen) {
                if (last_fid != -1 && fid != last_fid) {
                    /* FID toggled — previous frame just ended. */
                    if (frame_pos >= frame_size) {
                        /* Complete frame in buffer: return it now.
                         * Do NOT copy this packet; it belongs to the
                         * next frame and will be picked up next call. */
                        done = 1;
                        off += ctx->max_packet;
                        continue;
                    }
                    /* Incomplete frame — discard and start fresh. */
                    frame_pos = 0;
                }
                last_fid = fid;

                int payload = actual - hlen;
                int copy    = payload;
                if (frame_pos + copy > frame_size)
                    copy = frame_size - frame_pos;
                memcpy(frame + frame_pos, data + off + hlen, copy);
                frame_pos += copy;

                if (eof && frame_pos >= frame_size)
                    done = 1;
            }

            off += ctx->max_packet;
        }

        /* Re-queue this URB immediately. */
        submit_urb(ctx, idx);

        if (done) break;
    }

    (*env)->ReleaseByteArrayElements(env, frame_buf, frame, 0);
    return frame_pos;
}

/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_example_thermalviewer_UsbIsoReader_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle)
{
    IsoCtx *ctx = (IsoCtx *)(uintptr_t)handle;
    if (!ctx) return;

    ctx->running = 0;

    /* Discard all pending URBs so REAPURB unblocks. */
    for (int i = 0; i < NUM_URBS; i++) {
        if (ctx->urbs[i])
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->urbs[i]);
    }
    /* Drain the reap queue. */
    struct usbdevfs_urb *reaped;
    while (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped) == 0) {}

    for (int i = 0; i < NUM_URBS; i++) {
        free(ctx->urbs[i]);
        free(ctx->bufs[i]);
    }
    free(ctx);
}
