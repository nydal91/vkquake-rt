// Android: log file, error report and clean-exit marker for the launcher
// (see android/app/java/.../LauncherActivity.java), which shows them after a
// failed run. All files are in the app's files folder, which is $HOME (set by
// GameActivity) and the current directory.

#ifdef __ANDROID__

#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "android_sys.h"

#define LOG_FILE     "quake.log"
#define ERROR_FILE   "error.txt"
#define RUNNING_FILE "running.txt"

static FILE *logfile;

void Android_Startup (void)
{
	const char *home = getenv ("HOME");
	if (home && *home && chdir (home) != 0)
		__android_log_print (ANDROID_LOG_ERROR, "QuakeRT", "can't chdir to %s", home);

	logfile = fopen (LOG_FILE, "w");
	Android_SetRunning (1);
}

void Android_Log (const char *text)
{
	__android_log_write (ANDROID_LOG_INFO, "QuakeRT", text);
	if (logfile)
	{
		fputs (text, logfile);
		fflush (logfile);
	}
}

void Android_WriteError (const char *text)
{
	FILE *f = fopen (ERROR_FILE, "w");
	__android_log_write (ANDROID_LOG_ERROR, "QuakeRT", text);
	if (f)
	{
		fputs (text, f);
		fputc ('\n', f);
		fclose (f);
	}
	if (logfile)
	{
		fprintf (logfile, "\nERROR: %s\n", text);
		fflush (logfile);
	}
}

void Android_SetRunning (int running)
{
	if (running)
	{
		FILE *f = fopen (RUNNING_FILE, "w");
		if (f)
		{
			fputs ("running\n", f);
			fclose (f);
		}
	}
	else
		remove (RUNNING_FILE);
}

#endif
