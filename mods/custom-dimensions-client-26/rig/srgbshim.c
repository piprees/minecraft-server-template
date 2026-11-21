/* Rig-only GL shim: Xvfb's GLX offers no sRGB-capable visual, and Minecraft 26.3 asks SDL for one.
   Loaded through SDL_OPENGL_LIBRARY; drops GLX_FRAMEBUFFER_SRGB_CAPABLE_ARB from visual and fbconfig requests. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>
typedef void* (*choose_visual_fn)(void*, int, int*);
typedef void* (*choose_fbconfig_fn)(void*, int, const int*, int*);
typedef void* (*proc_fn)(const unsigned char*);
static void* real(const char* name) { static void* gl; if (!gl) gl = dlopen("libGL.so.1", RTLD_NOW | RTLD_GLOBAL); return dlsym(gl, name); }
static void strip(const int* in, int* out, int pairs) {
    int j = 0;
    for (int i = 0; in && in[i] != 0 && j < 254; ) {
        if (in[i] == 0x20B2) { i += 2; continue; }
        if (pairs) { out[j++] = in[i++]; out[j++] = in[i++]; }
        else { int a = in[i++]; out[j++] = a; if (a != 4 && a != 5 && a != 6) out[j++] = in[i++]; }
    }
    out[j] = 0;
}
void* glXChooseVisual(void* d, int s, int* attrs) { int a[256]; strip(attrs, a, 0); return ((choose_visual_fn) real("glXChooseVisual"))(d, s, a); }
void* glXChooseFBConfig(void* d, int s, const int* attrs, int* n) { int a[256]; strip(attrs, a, 1); return ((choose_fbconfig_fn) real("glXChooseFBConfig"))(d, s, a, n); }
static void* lookup(const unsigned char* name, const char* real_name) {
    if (!strcmp((const char*) name, "glXChooseVisual")) return (void*) glXChooseVisual;
    if (!strcmp((const char*) name, "glXChooseFBConfig")) return (void*) glXChooseFBConfig;
    return ((proc_fn) real(real_name))(name);
}
void* glXGetProcAddress(const unsigned char* name) { return lookup(name, "glXGetProcAddress"); }
void* glXGetProcAddressARB(const unsigned char* name) { return lookup(name, "glXGetProcAddressARB"); }
