#ifndef ANDROID_SYS_H
#define ANDROID_SYS_H

#ifdef __ANDROID__
// chdir to $HOME, open the log, mark the run as started
void Android_Startup (void);
// to logcat and the log file
void Android_Log (const char *text);
// error.txt, shown by the launcher on the next start
void Android_WriteError (const char *text);
// running.txt exists while the game runs; left behind by a crash
void Android_SetRunning (int running);
#endif

#endif
