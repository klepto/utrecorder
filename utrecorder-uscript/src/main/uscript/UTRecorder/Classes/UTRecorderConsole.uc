//=============================================================================
// UTRecorderConsole - console replacement that quits UT99 when a demo ends.
//
// Built for unattended demo playback. An external launcher:
//   1. writes System/UTRecorder.ini (see config vars below) with bAutoExit=True,
//   2. points [Engine.Engine] Console at UTRecorder.UTRecorderConsole,
//   3. starts the game with -EXEC=<file> holding a demoplay command.
// This class watches playback and issues "exit" once the demo runs out, so the
// process terminates on its own and the recorder can be stopped.
//
// Detection is layered, so it still terminates when udemo is not the demo
// driver in use:
//   1. udemo driver active  -> compare current frame against total frames,
//   2. spectator seen, gone -> playback torn down, quit,
//   3. neither happened     -> StartupTimeout / MaxRunSeconds backstops.
//
// Overriding the global Tick is enough to cover both console states:
// WindowConsole's state UWindow Tick dispatches through Global.Tick().
//=============================================================================
class UTRecorderConsole extends UTConsole
	config(UTRecorder);

const SHOW_PLAYERCTRL = 0x00000800;
const SHOW_REALTIME = 0x00004000;

// Master switch. Off by default so the class is harmless if it is left
// installed while the machine is used for normal play.
var config bool  bAutoExit;

// Seconds to keep running after the demo hits its last frame. Gives an
// external recorder time to capture the tail before the window disappears.
var config float ExitDelay;

// Quit if no demo spectator has appeared this many seconds after startup.
// Catches a bad demo name or a wrong DemoRecordingDevice. 0 disables.
var config float StartupTimeout;

// Absolute wall-clock cap on the whole session. 0 disables.
var config float MaxRunSeconds;

// Run "stopdemo" before "exit" - mirrors what udemo's own UI does.
var config bool  bStopDemoFirst;

var config bool  bVerbose;

var config float PlaybackFrameRate;
var config int SpawnHealth;
var config float SpawnTimeout;

var bool bNoRendering;
var bool bSignalledSpawn;
var bool bSeenPlayer;
var bool bSeenDead;
var float SincePlayback;
var float LastStreamTime;
var int RenderedFrames;
var bool bMarkedStart;
var bool bMarkedEnd;

var float Elapsed;        // seconds since this console was created
var float SinceEnd;       // seconds since the demo last looked finished
var bool  bSeenSpec;      // a demo spectator has existed at some point
var bool  bExiting;       // teardown started, ignore further checks
var float ExitPending;    // seconds left between "stopdemo" and "exit"

// =============================================================================
// Tick ~ poll playback state
// =============================================================================
event Tick(float Delta)
{
	if (bNoRendering)
		StopRendering();

	if (PlaybackFrameRate > 0.0)
		CheckSpawn(Delta);

	Super.Tick(Delta);

	if (!bAutoExit)
		return;

	RenderedFrames++;

	if (bVerbose)
		LogClock(Delta);

	Elapsed += Delta;

	if (bExiting)
	{
		// Second stage: "stopdemo" was issued last tick, now leave.
		ExitPending -= Delta;
		if (ExitPending <= 0.0)
		{
			ExitPending = 10000.0; // do not re-issue while the engine unwinds
			ConsoleCommand("exit");
		}
		return;
	}

	if (MaxRunSeconds > 0.0 && Elapsed >= MaxRunSeconds)
	{
		BeginExit("hard cap of" @ MaxRunSeconds @ "seconds reached");
		return;
	}

	CheckPlayback(Delta);
}

exec function DisableRendering()
{
	bNoRendering = True;
	Log("UTRecorder: rendering disabled", 'UTRecorder');
}

function StopRendering()
{
	local PlayerPawn Player;

	if (Viewport == None || Viewport.Actor == None)
		return;

	Player = Viewport.Actor;
	Player.ShowFlags = Player.ShowFlags & ~(SHOW_REALTIME | SHOW_PLAYERCTRL);
}

exec function DemoFrames(float Rate)
{
	PlaybackFrameRate = Rate;
	Log("UTRecorder: locking playback to" @ Rate @ "rendered frames per demo second", 'UTRecorder');
}

function CheckSpawn(float Delta)
{
	local DemoPlaybackSpec Spec;
	local PlayerPawn P;

	if (bSignalledSpawn || Viewport == None || Viewport.Actor == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None || Spec.Driver == None)
		return;

	SincePlayback += Delta;
	P = Spec.PlayerLinked;

	if (P == None)
	{
		if (bSeenPlayer || (SpawnTimeout > 0.0 && SincePlayback >= SpawnTimeout))
			SignalSpawn("no player to follow");
		return;
	}

	if (!bSeenPlayer)
	{
		bSeenPlayer = True;
		bSeenDead = P.Health <= 0;
		if (!bSeenDead)
			SignalSpawn("already alive");
		return;
	}

	if (bSeenDead && P.Health >= SpawnHealth)
		SignalSpawn("health" @ P.Health);
	else if (SpawnTimeout > 0.0 && SincePlayback >= SpawnTimeout)
		SignalSpawn("no spawn within" @ SpawnTimeout @ "seconds");
}

function SignalSpawn(string Reason)
{
	local DemoPlaybackSpec Spec;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None || Spec.Driver == None)
		return;

	bSignalledSpawn = True;
	Log("UTRecorder: spawn -" @ Reason, 'UTRecorder');
	MarkTime("spawn");
	Spec.Driver.SetSpeed(1.0);
}

function LogClock(float Delta)
{
	local DemoPlaybackSpec Spec;
	local LevelInfo L;
	local float Stream;

	if (Viewport == None || Viewport.Actor == None)
		return;

	L = Viewport.Actor.Level;
	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec != None && Spec.Driver != None)
		Stream = Spec.Driver.GetCurrentTime();

	Log("UTRecorder: f" @ RenderedFrames @ "tick" @ Delta @ "level" @ L.TimeSeconds
		@ "stream" @ Stream @ "dil" @ L.TimeDilation, 'UTRecorder');
}

function MarkTime(string Label)
{
	local DemoPlaybackSpec Spec;
	local LevelInfo L;
	local float Now;

	if (Viewport == None || Viewport.Actor == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec != None && Spec.Driver != None)
		Now = Spec.Driver.GetCurrentTime();
	else
		Now = LastStreamTime;

	L = Viewport.Actor.Level;
	Log("UTRecorder: mark" @ Label @ L.Year @ L.Month @ L.Day @ L.Hour @ L.Minute @ L.Second @ L.Millisecond
		@ RenderedFrames @ Now, 'UTRecorder');
}

// =============================================================================
// CheckPlayback ~ decide whether the demo is over
// =============================================================================
function CheckPlayback(float Delta)
{
	local DemoPlaybackSpec Spec;
	local int Cur, Total;

	if (Viewport == None || Viewport.Actor == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);

	if (Spec == None)
	{
		// Playback existed and has been torn down - the demo is over.
		if (bSeenSpec)
		{
			if (!bMarkedEnd)
			{
				bMarkedEnd = True;
				MarkTime("end");
			}

			SinceEnd += Delta;
			if (SinceEnd >= ExitDelay)
				BeginExit("demo spectator gone");
			return;
		}

		if (StartupTimeout > 0.0 && Elapsed >= StartupTimeout)
			BeginExit("no demo spectator after" @ StartupTimeout @ "seconds");

		return;
	}

	bSeenSpec = True;

	// Driver interface is only populated once playback is actually running.
	if (Spec.Driver == None)
		return;

	LastStreamTime = Spec.Driver.GetCurrentTime();

	Total = Spec.Driver.GetTotalFrames();
	Cur   = Spec.Driver.GetCurrentFrame();

	if (!bMarkedStart && Total > 0 && Cur > 0)
	{
		bMarkedStart = True;
		MarkTime("start");
	}

	if (Total <= 0)
		return;

	if (Cur < Total - 1)
	{
		SinceEnd = 0.0; // seeking backwards counts as still playing
		return;
	}

	if (!bMarkedEnd)
	{
		bMarkedEnd = True;
		MarkTime("end");
	}

	SinceEnd += Delta;

	if (bVerbose)
		Log("UTRecorder: at end frame" @ Cur $ "/" $ Total $ "," @ SinceEnd @ "of" @ ExitDelay, 'UTRecorder');

	if (SinceEnd >= ExitDelay)
		BeginExit("demo reached frame" @ Cur @ "of" @ Total);
}

// =============================================================================
// BeginExit ~ stop playback, then quit on the following tick
// =============================================================================
function BeginExit(string Reason)
{
	bExiting = True;

	Log("UTRecorder: quitting -" @ Reason, 'UTRecorder');
	Log("UTRecorder: rendered" @ RenderedFrames @ "frames", 'UTRecorder');

	if (bStopDemoFirst)
	{
		ConsoleCommand("stopdemo");
		ExitPending = 0.25;
	}
	else
		ExitPending = 0.0;
}

defaultproperties
{
	bAutoExit=False
	ExitDelay=0.0
	StartupTimeout=60.0
	MaxRunSeconds=0.0
	bStopDemoFirst=True
	bVerbose=False
	PlaybackFrameRate=0.0
	SpawnHealth=100
	SpawnTimeout=30.0
}
