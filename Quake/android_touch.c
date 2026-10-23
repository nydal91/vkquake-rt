// Touch controls for the Android build, see android_touch.h for the layout.

#include "quakedef.h"
#include "android_touch.h"

#ifdef HAVE_TOUCH_CONTROLS

#include <math.h>

extern cvar_t cl_maxpitch, cl_minpitch;

// zones, in normalized screen coordinates
#define TOP_BAR_H  0.16f
#define STICK_W    0.40f
#define SIDE_BTN_X 0.80f
#define FIRE_Y     0.58f

// stick: radius (fraction of screen height) for full speed, dead zone, run
#define STICK_RADIUS   0.14f
#define STICK_DEADZONE 0.12f // of the radius
#define STICK_RUN      0.92f // of the radius

// looking: degrees per screen width of drag
#define LOOK_YAW   200.0f
#define LOOK_PITCH 110.0f

// menus: drag distance that turns a tap into the stick, arrow key repeat
#define MENU_DRAG       0.04f
#define MENU_STICK      0.06f
#define MENU_REPEAT1_MS 400
#define MENU_REPEAT_MS  150

#define MAX_FINGERS 10

typedef enum
{
	ROLE_NONE,
	ROLE_STICK,
	ROLE_LOOK,
	ROLE_BUTTON,
	ROLE_TAP, // menu / console / demo: a tap, or a drag (becomes ROLE_STICK)
} role_t;

typedef struct
{
	SDL_FingerID id;
	role_t       role;
	float        x0, y0; // where it touched down
	float        x, y;   // current position
	const char  *cmd;    // ROLE_BUTTON: "+attack" style command, or a one-shot command
	int          key;    // ROLE_BUTTON: or a key
	Uint32       down_ms;
	int          down_frame;
} finger_t;

// Released buttons stay pressed until the game has run at least one frame
// with them down (and for a minimum time): at low frame rates a quick tap's
// down and up arrive in the same frame and would cancel out.
#define MIN_PRESS_MS 100
#define MAX_RELEASES 16

typedef struct
{
	const char *cmd;
	int         key;
	int         earliest_frame;
	Uint32      earliest_ms;
} release_t;

static release_t releases[MAX_RELEASES];
static int       release_count = 0;
static int       touch_frame = 0;

static finger_t fingers[MAX_FINGERS];
static float    pending_yaw, pending_pitch;
static qboolean controller_in_use = false;

typedef struct
{
	const char *label;
	const char *cmd; // "+x" commands are held, others run once
} button_t;

#define TOP_BUTTONS 5
static const button_t top_buttons[TOP_BUTTONS] = {
	{"MENU", NULL}, // escape key
	{"PREV W", "impulse 12"},
	{"NEXT W", "impulse 10"},
	{"LIGHT", "toggle rt_flashlight"},
	{"SCORES", "+showscores"},
};

static qboolean InMenu (void)
{
	return key_dest != key_game;
}

static qboolean InDemoLoop (void)
{
	return key_dest == key_game && cls.demoplayback && !cls.timedemo;
}

static void ButtonDown (const char *cmd, int key)
{
	if (cmd)
		Cbuf_AddText (va ("%s\n", cmd));
	else if (key)
		Key_Event (key, true);
}

static void ButtonUp (const char *cmd, int key)
{
	if (cmd)
	{
		if (cmd[0] == '+')
			Cbuf_AddText (va ("-%s\n", cmd + 1));
	}
	else if (key)
		Key_Event (key, false);
}

static void QueueRelease (const char *cmd, int key, Uint32 down_ms)
{
	if (release_count < MAX_RELEASES)
	{
		release_t *r = &releases[release_count++];
		r->cmd = cmd;
		r->key = key;
		r->earliest_frame = touch_frame + 1;
		r->earliest_ms = down_ms + MIN_PRESS_MS;
	}
	else
		ButtonUp (cmd, key);
}

static void PressKey (int key)
{
	if (key)
	{
		Key_Event (key, true);
		QueueRelease (NULL, key, SDL_GetTicks ());
	}
}

static finger_t *FindFinger (SDL_FingerID id)
{
	for (int i = 0; i < MAX_FINGERS; i++)
		if (fingers[i].role != ROLE_NONE && fingers[i].id == id)
			return &fingers[i];
	return NULL;
}

static finger_t *NewFinger (SDL_FingerID id)
{
	for (int i = 0; i < MAX_FINGERS; i++)
	{
		if (fingers[i].role == ROLE_NONE)
		{
			memset (&fingers[i], 0, sizeof (fingers[i]));
			fingers[i].id = id;
			return &fingers[i];
		}
	}
	return NULL;
}

static float Aspect (void)
{
	return vid.height > 0 ? (float)vid.width / (float)vid.height : 16.0f / 9.0f;
}

void AndroidTouch_Init (void)
{
	memset (fingers, 0, sizeof (fingers));

	// handle touches ourselves, without SDL turning them into mouse clicks
	SDL_SetHint (SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
	SDL_SetHint (SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
}

void AndroidTouch_ControllerUsed (void)
{
	controller_in_use = true;
}

qboolean AndroidTouch_HandleEvent (const SDL_Event *ev)
{
	switch (ev->type)
	{
	case SDL_FINGERDOWN:
	{
		float     x = ev->tfinger.x, y = ev->tfinger.y;
		finger_t *f;

		controller_in_use = false;
		f = NewFinger (ev->tfinger.fingerId);
		if (!f)
			return true;

		f->x0 = f->x = x;
		f->y0 = f->y = y;
		f->down_ms = SDL_GetTicks ();
		f->down_frame = touch_frame;

		if (InMenu () || InDemoLoop ())
		{
			if (y < TOP_BAR_H && x < 1.0f / TOP_BUTTONS)
			{
				f->role = ROLE_BUTTON;
				f->key = K_ESCAPE;
				Key_Event (K_ESCAPE, true);
			}
			else
				f->role = ROLE_TAP; // decided when the finger moves or lifts
		}
		else if (y < TOP_BAR_H)
		{
			int slot = CLAMP (0, (int)(x * TOP_BUTTONS), TOP_BUTTONS - 1);
			f->role = ROLE_BUTTON;
			f->cmd = top_buttons[slot].cmd;
			f->key = f->cmd ? 0 : K_ESCAPE;
			ButtonDown (f->cmd, f->key);
		}
		else if (x < STICK_W)
			f->role = ROLE_STICK;
		else if (x >= SIDE_BTN_X)
		{
			f->role = ROLE_BUTTON;
			f->cmd = y >= FIRE_Y ? "+attack" : "+jump";
			ButtonDown (f->cmd, 0);
		}
		else
			f->role = ROLE_LOOK;
		return true;
	}

	case SDL_FINGERMOTION:
	{
		finger_t *f = FindFinger (ev->tfinger.fingerId);
		if (f)
		{
			if (f->role == ROLE_LOOK)
			{
				pending_yaw += ev->tfinger.dx * LOOK_YAW;
				pending_pitch += ev->tfinger.dy * LOOK_PITCH;
			}
			f->x = ev->tfinger.x;
			f->y = ev->tfinger.y;
			if (f->role == ROLE_TAP && fabsf (f->x - f->x0) + fabsf (f->y - f->y0) > MENU_DRAG)
				f->role = ROLE_STICK;
		}
		return true;
	}

	case SDL_FINGERUP:
	{
		finger_t *f = FindFinger (ev->tfinger.fingerId);
		if (f)
		{
			if (f->role == ROLE_BUTTON)
			{
				// one-shot commands already ran on the press
				if (!f->cmd || f->cmd[0] == '+')
					QueueRelease (f->cmd, f->key, f->down_ms);
			}
			else if (f->role == ROLE_TAP)
			{
				// demo loop: any tap opens the menu, like any key would
				PressKey (InDemoLoop () ? K_ESCAPE : K_ENTER);
			}
			f->role = ROLE_NONE;
		}
		return true;
	}

	case SDL_KEYDOWN:
	case SDL_KEYUP:
		// Android back button: open / close the menu
		if (ev->key.keysym.sym == SDLK_AC_BACK)
		{
			Key_Event (K_ESCAPE, ev->type == SDL_KEYDOWN);
			return true;
		}
		return false;

	case SDL_MOUSEMOTION:
		return ev->motion.which == SDL_TOUCH_MOUSEID;
	case SDL_MOUSEBUTTONDOWN:
	case SDL_MOUSEBUTTONUP:
		return ev->button.which == SDL_TOUCH_MOUSEID;

	default:
		return false;
	}
}

static finger_t *StickFinger (float *dx, float *dy)
{
	for (int i = 0; i < MAX_FINGERS; i++)
	{
		if (fingers[i].role == ROLE_STICK)
		{
			// in units of the screen height on both axes
			*dx = (fingers[i].x - fingers[i].x0) * Aspect ();
			*dy = fingers[i].y - fingers[i].y0;
			return &fingers[i];
		}
	}
	*dx = *dy = 0;
	return NULL;
}

void AndroidTouch_Frame (void)
{
#ifdef QUAKE_TOUCH_TEST
	void AndroidTouch_TestInject (void);
	AndroidTouch_TestInject ();
#endif
	float dx, dy;

	touch_frame++;

	for (int i = 0; i < release_count;)
	{
		if (touch_frame > releases[i].earliest_frame && SDL_TICKS_PASSED (SDL_GetTicks (), releases[i].earliest_ms))
		{
			ButtonUp (releases[i].cmd, releases[i].key);
			releases[i] = releases[--release_count];
		}
		else
			i++;
	}

	// menus move with the arrow keys, one step per push, repeating when held
	{
		static int    menu_dir = 0;
		static Uint32 next_repeat = 0;
		int           dir = 0;
		Uint32        now = SDL_GetTicks ();

		if (InMenu () && StickFinger (&dx, &dy) && fabsf (dx) + fabsf (dy) > MENU_STICK)
		{
			if (fabsf (dy) >= fabsf (dx))
				dir = dy < 0 ? K_UPARROW : K_DOWNARROW;
			else
				dir = dx < 0 ? K_LEFTARROW : K_RIGHTARROW;
		}
		if (dir != menu_dir)
		{
			menu_dir = dir;
			PressKey (dir);
			next_repeat = now + MENU_REPEAT1_MS;
		}
		else if (dir && SDL_TICKS_PASSED (now, next_repeat))
		{
			PressKey (dir);
			next_repeat = now + MENU_REPEAT_MS;
		}
	}
}

void AndroidTouch_Move (usercmd_t *cmd)
{
	float dx, dy, len, nx, ny, speed;

	if (InMenu ())
	{
		pending_yaw = pending_pitch = 0;
		return;
	}

	if (StickFinger (&dx, &dy))
	{
		nx = dx / STICK_RADIUS;
		ny = dy / STICK_RADIUS;
		len = sqrtf (nx * nx + ny * ny);
		if (len > STICK_DEADZONE)
		{
			float scale = (q_min (len, 1.0f) - STICK_DEADZONE) / (1.0f - STICK_DEADZONE) / len;
			qboolean run = len > STICK_RUN || cl_alwaysrun.value != 0.0f;

			speed = run ? cl_movespeedkey.value : 1.0f;
			cmd->sidemove += cl_sidespeed.value * speed * nx * scale;
			cmd->forwardmove -= cl_forwardspeed.value * speed * ny * scale;
		}
	}

	if (pending_yaw != 0.0f || pending_pitch != 0.0f)
	{
		cl.viewangles[YAW] -= pending_yaw * sensitivity.value / 3.0f;
		cl.viewangles[PITCH] += pending_pitch * sensitivity.value / 3.0f;
		pending_yaw = pending_pitch = 0;
		V_StopPitchDrift ();
		if (cl.viewangles[PITCH] > cl_maxpitch.value)
			cl.viewangles[PITCH] = cl_maxpitch.value;
		if (cl.viewangles[PITCH] < cl_minpitch.value)
			cl.viewangles[PITCH] = cl_minpitch.value;
	}
}

// --- drawing, in CANVAS_TOUCH units (240 high)

#define GRAY  8
#define WHITE 15

static void Outline (cb_context_t *cbx, float x, float y, float w, float h, int color, float alpha)
{
	Draw_Fill (cbx, (int)x, (int)y, (int)w, 1, color, alpha);
	Draw_Fill (cbx, (int)x, (int)(y + h - 1), (int)w, 1, color, alpha);
	Draw_Fill (cbx, (int)x, (int)y, 1, (int)h, color, alpha);
	Draw_Fill (cbx, (int)(x + w - 1), (int)y, 1, (int)h, color, alpha);
}

static void Label (cb_context_t *cbx, float cx, float cy, const char *text)
{
	Draw_String (cbx, (int)(cx - 4.0f * strlen (text)), (int)(cy - 4.0f), text);
}

void AndroidTouch_Draw (cb_context_t *cbx)
{
	const float H = 240.0f, W = 240.0f * Aspect ();
	float       dx, dy;

	if (controller_in_use)
		return;

	GL_SetCanvas (cbx, CANVAS_TOUCH);

	if (InMenu () || InDemoLoop ())
	{
		if (key_dest != key_console || InDemoLoop ())
		{
			Outline (cbx, 2, 2, W / TOP_BUTTONS - 4, TOP_BAR_H * H - 4, GRAY, 0.6f);
			Label (cbx, W / TOP_BUTTONS * 0.5f, TOP_BAR_H * H * 0.5f, InDemoLoop () ? "MENU" : "BACK");
		}
		finger_t *f = StickFinger (&dx, &dy);
		if (f)
			Outline (cbx, W * f->x - 4, H * f->y - 4, 8, 8, WHITE, 0.8f);
		return;
	}
	if (cls.state != ca_connected || cl.intermission)
	{
		return;
	}

	for (int i = 0; i < TOP_BUTTONS; i++)
	{
		Outline (cbx, i * W / TOP_BUTTONS + 2, 2, W / TOP_BUTTONS - 4, TOP_BAR_H * H - 4, GRAY, 0.6f);
		Label (cbx, (i + 0.5f) * W / TOP_BUTTONS, TOP_BAR_H * H * 0.5f, top_buttons[i].label);
	}

	Outline (cbx, SIDE_BTN_X * W + 2, TOP_BAR_H * H + 2, (1.0f - SIDE_BTN_X) * W - 4, (FIRE_Y - TOP_BAR_H) * H - 4, GRAY, 0.6f);
	Outline (cbx, SIDE_BTN_X * W + 2, FIRE_Y * H + 2, (1.0f - SIDE_BTN_X) * W - 4, (1.0f - FIRE_Y) * H - 6, GRAY, 0.6f);
	Label (cbx, (1.0f + SIDE_BTN_X) * 0.5f * W, (TOP_BAR_H + FIRE_Y) * 0.5f * H, "JUMP");
	Label (cbx, (1.0f + SIDE_BTN_X) * 0.5f * W, (FIRE_Y + 1.0f) * 0.5f * H, "FIRE");

	for (int i = 0; i < MAX_FINGERS; i++)
	{
		if (fingers[i].role == ROLE_STICK)
		{
			float cx = fingers[i].x0 * W, cy = fingers[i].y0 * H, r = STICK_RADIUS * H;
			Outline (cbx, cx - r, cy - r, 2 * r, 2 * r, GRAY, 0.6f);
			Outline (cbx, fingers[i].x * W - 4, fingers[i].y * H - 4, 8, 8, WHITE, 0.8f);
		}
	}
	if (!StickFinger (&dx, &dy))
		Label (cbx, STICK_W * 0.5f * W, 0.8f * H, "MOVE");
}

#ifdef QUAKE_TOUCH_TEST
// Desktop test: QUAKE_TOUCH_SCRIPT="frame,d|m|u,fingerid,x,y;..." pushes SDL
// finger events through SDL's queue at the given frames, like Android would.
void AndroidTouch_TestInject (void)
{
	static unsigned char done[256];
	static float         last_x[16], last_y[16];
	const char          *script = getenv ("QUAKE_TOUCH_SCRIPT");
	const char          *p = script;
	int                  index = 0;

	for (; p && *p; index++)
	{
		int   frame, id;
		char  t;
		float x, y;
		if (sscanf (p, "%d,%c,%d,%f,%f", &frame, &t, &id, &x, &y) == 5 && frame <= touch_frame && index < (int)sizeof (done) && !done[index])
		{
			SDL_Event e;
			done[index] = 1;
			memset (&e, 0, sizeof (e));
			e.type = t == 'd' ? SDL_FINGERDOWN : t == 'u' ? SDL_FINGERUP : SDL_FINGERMOTION;
			e.tfinger.fingerId = id;
			e.tfinger.x = x;
			e.tfinger.y = y;
			if (id >= 0 && id < 16)
			{
				e.tfinger.dx = t == 'd' ? 0 : x - last_x[id];
				e.tfinger.dy = t == 'd' ? 0 : y - last_y[id];
				last_x[id] = x;
				last_y[id] = y;
			}
			SDL_PushEvent (&e);
			Sys_Printf ("touchtest: frame %d push %c finger %d at %.2f,%.2f\n", touch_frame, t, id, x, y);
		}
		p = strchr (p, ';');
		if (p)
			p++;
	}
	if (cls.state == ca_connected && touch_frame % 30 == 0)
		Sys_Printf (
			"touchtest: frame %d key_dest %d pos %.0f %.0f %.0f yaw %.1f pitch %.1f weapon %d\n", touch_frame, key_dest, cl.entities[cl.viewentity].origin[0],
			cl.entities[cl.viewentity].origin[1], cl.entities[cl.viewentity].origin[2], cl.viewangles[YAW], cl.viewangles[PITCH], cl.stats[STAT_ACTIVEWEAPON]);
}
#endif

#endif // HAVE_TOUCH_CONTROLS
