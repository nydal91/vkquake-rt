// Touch controls for the Android build.
//
// In game:
//   top bar:        MENU | PREV W | NEXT W | LIGHT | SCORES
//   left 40%:       movement stick (where the finger lands is the center;
//                   past the outer ring it runs)
//   middle:         drag to look around
//   right column:   JUMP (upper), FIRE (lower)
// In menus: drag = arrow keys, tap = enter, top-left = back.
// The overlay hides while a game controller is in use and comes back on touch.

#ifndef ANDROID_TOUCH_H
#define ANDROID_TOUCH_H

#if defined(__ANDROID__) || defined(QUAKE_TOUCH_TEST)
#define HAVE_TOUCH_CONTROLS 1
#endif

#ifdef HAVE_TOUCH_CONTROLS



// sets SDL hints; call before SDL_Init
void AndroidTouch_Init (void);

// true if the event was a touch event and was handled
qboolean AndroidTouch_HandleEvent (const SDL_Event *ev);

// once per frame, after the events: delayed button releases, menu arrows
void AndroidTouch_Frame (void);

// adds stick movement and look to the command (from IN_Move)
void AndroidTouch_Move (usercmd_t *cmd);

// draws the overlay (end of the 2D pass)
void AndroidTouch_Draw (cb_context_t *cbx);

// a game controller was used: hide the overlay until the next touch
void AndroidTouch_ControllerUsed (void);

#endif

#endif
