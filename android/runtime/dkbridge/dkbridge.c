/*
 * dkbridge - charge via LD_PRELOAD dans le moteur Daikatana (runtime Linux de l'appli).
 *
 * Ajoute une manette SDL virtuelle et recoit les entrees de l'appli Android sur une
 * socket TCP locale (port DK_INPUT_PORT). La manette est "branchee" apres les premieres
 * images : le moteur n'ouvre une manette que sur SDL_CONTROLLERDEVICEADDED, et cet
 * evenement serait perdu s'il arrivait pendant son initialisation. Messages de 8 octets, little-endian :
 *   uint8 type, uint8 reserve, uint16 code, int32 valeur
 *   type 1 = bouton manette (code = SDL_GameControllerButton, valeur 0/1)
 *   type 2 = axe manette    (code = SDL_GameControllerAxis, valeur -32768..32767)
 *   type 3 = touche clavier (code = SDL_Scancode, valeur 0/1)
 *   type 4 = pause (valeur 1) / reprise (valeur 0) : appli en arriere-plan ou ecran verrouille.
 *            La boucle du jeu est bloquee a la fin de l'image en cours.
 */
#define _GNU_SOURCE
#include <SDL2/SDL.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define MSG_BUTTON 1
#define MSG_AXIS   2
#define MSG_KEY    3
#define MSG_PAUSE  4

#define ATTACH_AFTER_FRAMES 30

static SDL_Joystick *volatile virtual_joy;
static int started;
static int frames;
static int paused;
static FILE *stats_file;
static uint32_t stats_start;
static unsigned stats_frames;
static pthread_mutex_t pause_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t pause_cond = PTHREAD_COND_INITIALIZER;

static int read_full(int fd, void *buf, size_t len)
{
    uint8_t *p = buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n <= 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static void push_key(SDL_Scancode scancode, int down)
{
    SDL_Event ev;
    SDL_memset(&ev, 0, sizeof(ev));
    ev.type = down ? SDL_KEYDOWN : SDL_KEYUP;
    ev.key.timestamp = SDL_GetTicks();
    ev.key.state = down ? SDL_PRESSED : SDL_RELEASED;
    ev.key.keysym.scancode = scancode;
    ev.key.keysym.sym = SDL_GetKeyFromScancode(scancode);
    SDL_PushEvent(&ev);
}

void dklang_frame(void); /* dklang.c */

static void set_paused(int value)
{
    pthread_mutex_lock(&pause_lock);
    paused = value ? 1 : 0;
    pthread_cond_broadcast(&pause_cond);
    pthread_mutex_unlock(&pause_lock);
}

/* Appele par le thread du jeu apres chaque image : attend tant que l'appli est en pause. */
static void wait_while_paused(void)
{
    pthread_mutex_lock(&pause_lock);
    while (paused) {
        stats_start = 0; stats_frames = 0;
        pthread_cond_wait(&pause_cond, &pause_lock);
    }
    pthread_mutex_unlock(&pause_lock);
}

static void handle(uint8_t type, uint16_t code, int32_t value)
{
    switch (type) {
    case MSG_BUTTON:
        if (virtual_joy) SDL_JoystickSetVirtualButton(virtual_joy, code, value ? 1 : 0);
        break;
    case MSG_AXIS:
        if (virtual_joy) SDL_JoystickSetVirtualAxis(virtual_joy, code, (Sint16)value);
        break;
    case MSG_KEY:
        push_key((SDL_Scancode)code, value);
        break;
    case MSG_PAUSE:
        set_paused(value);
        break;
    }
}

static void *input_thread(void *arg)
{
    const char *port_env = getenv("DK_INPUT_PORT");
    int port = port_env ? atoi(port_env) : 0;
    (void)arg;
    if (port <= 0) return NULL;

    for (;;) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons((uint16_t)port);
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (fd >= 0 && connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            int one = 1;
            uint8_t msg[8];
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
            fprintf(stderr, "dkbridge: entrees connectees (port %d)\n", port);
            while (read_full(fd, msg, sizeof(msg)) == 0) {
                uint16_t code = (uint16_t)(msg[2] | (msg[3] << 8));
                int32_t value = (int32_t)((uint32_t)msg[4] | ((uint32_t)msg[5] << 8) |
                                          ((uint32_t)msg[6] << 16) | ((uint32_t)msg[7] << 24));
                handle(msg[0], code, value);
            }
        }
        if (fd >= 0) close(fd);
        set_paused(0); /* appli deconnectee : ne jamais rester bloque */
        usleep(200 * 1000);
    }
    return NULL;
}

static void attach_virtual_controller(void)
{
    SDL_VirtualJoystickDesc desc;
    int index;
    char guid[64];
    char mapping[512];

    if (SDL_WasInit(SDL_INIT_JOYSTICK) == 0 && SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {
        fprintf(stderr, "dkbridge: SDL_InitSubSystem: %s\n", SDL_GetError());
        return;
    }

    SDL_zero(desc);
    desc.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
    desc.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
    desc.naxes = SDL_CONTROLLER_AXIS_MAX;
    desc.nbuttons = SDL_CONTROLLER_BUTTON_MAX;
    desc.name = "Android Gamepad";

    index = SDL_JoystickAttachVirtualEx(&desc);
    if (index < 0) {
        fprintf(stderr, "dkbridge: SDL_JoystickAttachVirtualEx: %s\n", SDL_GetError());
        return;
    }

    /* Mapping explicite : boutons et axes dans l'ordre des enums SDL_GameController. */
    SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(index), guid, sizeof(guid));
    snprintf(mapping, sizeof(mapping),
             "%s,Android Gamepad,a:b0,b:b1,x:b2,y:b3,back:b4,guide:b5,start:b6,"
             "leftstick:b7,rightstick:b8,leftshoulder:b9,rightshoulder:b10,"
             "dpup:b11,dpdown:b12,dpleft:b13,dpright:b14,"
             "leftx:a0,lefty:a1,rightx:a2,righty:a3,lefttrigger:a4,righttrigger:a5,",
             guid);
    SDL_GameControllerAddMapping(mapping);

    virtual_joy = SDL_JoystickOpen(index);
    if (!virtual_joy) fprintf(stderr, "dkbridge: SDL_JoystickOpen: %s\n", SDL_GetError());
    else fprintf(stderr, "dkbridge: manette virtuelle branchee (index %d)\n", index);
}

static void bridge_start(void)
{
    pthread_t thread;
    if (started) return;
    started = 1;
    const char *dir = getenv("DK_LOG_DIR");
    if (dir) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/render-fps.txt", dir);
        stats_file = fopen(path, "w");
    }
    if (pthread_create(&thread, NULL, input_thread, NULL) == 0) pthread_detach(thread);
}

int SDL_Init(Uint32 flags)
{
    static int (*real_init)(Uint32);
    int ret;
    if (!real_init) real_init = (int (*)(Uint32))dlsym(RTLD_NEXT, "SDL_Init");
    ret = real_init(flags);
    if (ret == 0) bridge_start();
    return ret;
}

void SDL_GL_SwapWindow(SDL_Window *window)
{
    static void (*real_swap)(SDL_Window *);
    if (!real_swap) real_swap = (void (*)(SDL_Window *))dlsym(RTLD_NEXT, "SDL_GL_SwapWindow");
    real_swap(window);
    if (stats_file) {
        uint32_t now = SDL_GetTicks();
        if (!stats_start) stats_start = now;
        else if (++stats_frames && (uint32_t)(now - stats_start) >= 1000) {
            rewind(stats_file);
            fprintf(stats_file, "%.1f\n", stats_frames * 1000.0 / (uint32_t)(now - stats_start));
            fflush(stats_file);
            ftruncate(fileno(stats_file), ftell(stats_file));
            stats_frames = 0; stats_start = now;
        }
    }
    dklang_frame();
    wait_while_paused();
    if (++frames == ATTACH_AFTER_FRAMES) attach_virtual_controller();
    if (getenv("DK_DEBUG") && frames % 100 == 0) {
        fprintf(stderr, "dkbridge: image %d, t=%u ms\n", frames, SDL_GetTicks());
    }
}
