//=============================================================================
// UTRecorderConsole - console that drives unattended demo playback and quits
// the game once the demo ends.
//
// Configured through System/UTRecorder.ini and installed as the
// [Engine.Engine] Console. It does nothing until bAutoExit is on. While it
// is on, the console logs a start and an end mark around the demo, then
// stops playback and exits the process once the demo is over, the demo
// spectator is gone, or a timeout runs out. It also stops warp zones from
// moving actors on the client, since the demo already carries every position.
//
// Exec commands, typically run from an -EXEC script:
//   DisableRendering  stop drawing the world,
//   HideMessages      keep chat and progress messages off the HUD,
//   RewindStart       seek back to the demo start once ticks settle,
//   DemoFrames <fps>  log a spawn mark and reset the demo speed to 1 once the
//                     followed player spawns,
//   TimerReset <demo time>
//                     make DemoFrames log the spawn mark instead once the demo
//                     reaches the demo time the Bunny Track timer of the run
//                     resets at,
//   SmoothRecorder <interval> <movement> <rotation>
//                     smooth the followed player's movement and view with a
//                     UTRecorderSmooth, for a demo whose frames are
//                     <interval> seconds apart, showing his movement and his
//                     view the given seconds behind the demo.
//=============================================================================
class UTRecorderConsole extends UTConsole
	config(UTRecorder);

const SHOW_PLAYERCTRL = 0x00000800;
const SHOW_REALTIME = 0x00004000;

// A tick shorter than this counts as settled for RewindStart.
const SETTLED_DELTA = 0.05;
// Settled ticks in a row RewindStart waits for before seeking back.
const SETTLED_TICKS = 10;

// Master switch. Off by default, so the console behaves like UTConsole while
// it stays installed for normal play.
var config bool  bAutoExit;

// Seconds to keep running after the demo hits its last frame.
var config float ExitDelay;

// Quit if no demo spectator has appeared this many seconds after startup.
// 0 disables.
var config float StartupTimeout;

// Wall-clock cap on the whole session, in seconds. 0 disables.
var config float MaxRunSeconds;

// Run "stopdemo" before "exit".
var config bool  bStopDemoFirst;

// Log the clocks on every tick and the end-frame wait.
var config bool  bVerbose;

// Rendered frames per demo second DemoFrames set. Above 0, the console
// watches for the followed player's spawn.
var config float PlaybackFrameRate;

// Health at which a dead followed player counts as spawned.
var config int SpawnHealth;

// Seconds of playback after which the spawn is signalled anyway. 0 disables.
var config float SpawnTimeout;

var bool bNoRendering;    // DisableRendering was run
var bool bHideMessages;   // HideMessages was run
var bool bSignalledSpawn; // the spawn mark has been logged
var bool bSeenPlayer;     // a followed player has existed at some point
var bool bSeenDead;       // the first followed player seen was dead
var float SincePlayback;  // seconds of playback the spawn check has seen
var float RunStart;       // demo time TimerReset set, 0 when unknown
var float LastStreamTime; // demo time of the last tick that had a driver
var int RenderedFrames;   // ticks counted while bAutoExit is on
var bool bMarkedStart;    // the start mark has been logged
var bool bMarkedEnd;      // the end mark has been logged
var bool bRewindStart;    // seek back to the demo's start once ticks settle
var bool bRewound;        // the rewind seek has been issued
var int SettledTicks;     // settled ticks in a row before the rewind
var bool bSmoothRecorder; // SmoothRecorder was run
var float SmoothInterval, SmoothMovement, SmoothRotation; // what SmoothRecorder was run with
var UTRecorderSmooth Smoother; // the smoother spawned in the demo level

var float Elapsed;        // seconds since this console was created
var float SinceEnd;       // seconds since the demo last looked finished
var bool  bSeenSpec;      // a demo spectator has existed at some point
var bool  bExiting;       // teardown started, ignore further checks
var float ExitPending;    // seconds left between "stopdemo" and "exit"

// Logs a mark labelled Label, with the wall-clock time, the rendered frame
// count and the demo time. Without a demo driver, the demo time is the last
// one seen.
function MarkTime(string Label)
{
	local DemoPlaybackSpec Spec;
	local LevelInfo CurrentLevel;
	local float DemoTime;

	if (Viewport == None || Viewport.Actor == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec != None && Spec.Driver != None)
		DemoTime = Spec.Driver.GetCurrentTime();
	else
		DemoTime = LastStreamTime;

	CurrentLevel = Viewport.Actor.Level;
	Log("UTRecorder: mark" @ Label @ CurrentLevel.Year @ CurrentLevel.Month @ CurrentLevel.Day @ CurrentLevel.Hour
		@ CurrentLevel.Minute @ CurrentLevel.Second @ CurrentLevel.Millisecond @ RenderedFrames @ DemoTime, 'UTRecorder');
}

// Logs the end mark once.
function MarkEnd()
{
	if (bMarkedEnd)
		return;

	bMarkedEnd = True;
	MarkTime("end");
}

// Applies the exec commands that were run, then polls playback and advances
// the exit while bAutoExit is on.
event Tick(float Delta)
{
	if (bNoRendering)
		StopRendering();

	if (bHideMessages)
		ClearHUDMessages();

	if (PlaybackFrameRate > 0.0)
		CheckSpawn(Delta);

	if (bSmoothRecorder)
		SpawnSmoother();

	if (bAutoExit)
		DisableWarpSimulation();

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

// Unlinks every warp zone of the demo level from its other side, so that
// ActorEntered no longer moves an actor that enters it.
function DisableWarpSimulation()
{
	local DemoPlaybackSpec Spec;
	local WarpZoneInfo Zone;

	if (Viewport == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None)
		return;

	foreach Spec.AllActors(class'WarpZoneInfo', Zone)
	{
		if (Zone.OtherSideActor == None && Zone.OtherSideLevel != None)
			continue;

		Zone.OtherSideLevel = Zone.XLevel;
		Zone.OtherSideActor = None;
		Log("UTRecorder: disabled warp simulation of" @ Zone, 'UTRecorder');
	}
}

// Logs the tick delta and the level and demo clocks.
function LogClock(float Delta)
{
	local DemoPlaybackSpec Spec;
	local LevelInfo CurrentLevel;
	local float StreamTime;

	if (Viewport == None || Viewport.Actor == None)
		return;

	CurrentLevel = Viewport.Actor.Level;
	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec != None && Spec.Driver != None)
		StreamTime = Spec.Driver.GetCurrentTime();

	Log("UTRecorder: f" @ RenderedFrames @ "tick" @ Delta @ "level" @ CurrentLevel.TimeSeconds
		@ "stream" @ StreamTime @ "dil" @ CurrentLevel.TimeDilation, 'UTRecorder');
}

// Stops drawing the world from the next tick on.
exec function DisableRendering()
{
	bNoRendering = True;
	Log("UTRecorder: rendering disabled", 'UTRecorder');
}

// Clears the realtime and player control show flags of the viewport actor.
function StopRendering()
{
	local PlayerPawn Player;

	if (Viewport == None || Viewport.Actor == None)
		return;

	Player = Viewport.Actor;
	Player.ShowFlags = Player.ShowFlags & ~(SHOW_REALTIME | SHOW_PLAYERCTRL);
}

// Keeps chat, BTPog lines and BTPog's stats block off the HUD from the next
// tick on, while the timer and the rest of the HUD still draw.
exec function HideMessages()
{
	bHideMessages = True;
	Log("UTRecorder: hiding HUD messages", 'UTRecorder');
}

// Expires the progress message of the demo spectator and the linked player,
// and mutes the linked player's HUD while it is still udemo's InterceptHUD.
function ClearHUDMessages()
{
	local DemoPlaybackSpec Spec;
	local PlayerPawn Linked;

	if (Viewport == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None)
		return;

	Spec.ProgressTimeOut = 0;
	Linked = Spec.PlayerLinked;
	if (Linked == None)
		return;

	Linked.ProgressTimeOut = 0;
	if (InterceptHUD(Linked.myHUD) != None && UTRecorderHUD(Linked.myHUD) == None)
		MuteHUD(Spec, Linked);
}

// Replaces the HUD of Linked with a UTRecorderHUD that forwards to Spec.
function MuteHUD(DemoPlaybackSpec Spec, PlayerPawn Linked)
{
	local UTRecorderHUD HUD;

	HUD = Spec.Spawn(class'UTRecorderHUD', Linked);
	HUD.Real = Spec;
	Linked.myHUD.Destroy();
	Linked.myHUD = HUD;
	Log("UTRecorder: muted the HUD of" @ Linked.PlayerReplicationInfo.PlayerName, 'UTRecorder');
}

// Seeks back to the demo's start once ticks settle, before the start mark.
exec function RewindStart()
{
	bRewindStart = True;
	Log("UTRecorder: rewinding to the demo start once ticks settle", 'UTRecorder');
}

// True while the rewind RewindStart asked for is pending or still seeking.
// Issues the seek once SETTLED_TICKS ticks in a row are shorter than
// SETTLED_DELTA.
function bool Rewinding(DemoPlaybackSpec Spec, float Delta)
{
	if (!bRewindStart)
		return False;

	if (bRewound)
		return Spec.SeekTick > 0 || Spec.bSeeking;

	if (Delta < SETTLED_DELTA)
		SettledTicks++;
	else
		SettledTicks = 0;

	if (SettledTicks < SETTLED_TICKS)
		return True;

	bRewound = True;
	Log("UTRecorder: rewinding from" @ Spec.Driver.GetCurrentTime(), 'UTRecorder');
	Spec.SeekTo("0");
	return True;
}

// Keeps a UTRecorderSmooth spawned in the demo level from the next tick on.
// Args holds three space-separated numbers: the seconds between the demo's
// frames, and how many seconds behind the demo the followed player's
// movement and his view are shown.
exec function SmoothRecorder(string Args)
{
	bSmoothRecorder = True;
	SmoothInterval = float(NextArgument(Args));
	SmoothMovement = float(NextArgument(Args));
	SmoothRotation = float(NextArgument(Args));
	Log("UTRecorder: smoothing the followed player, demo frame interval" @ SmoothInterval @ "s, movement shown"
		@ SmoothMovement @ "s behind, rotation" @ SmoothRotation @ "s", 'UTRecorder');
}

// Returns the first space-separated word of Args and removes it from Args.
function string NextArgument(out string Args)
{
	local int Space;
	local string Word;

	while (Left(Args, 1) == " ")
		Args = Mid(Args, 1);

	Space = InStr(Args, " ");
	if (Space < 0)
	{
		Word = Args;
		Args = "";
		return Word;
	}

	Word = Left(Args, Space);
	Args = Mid(Args, Space + 1);
	return Word;
}

// Spawns a UTRecorderSmooth in the demo level unless one is still there.
function SpawnSmoother()
{
	local DemoPlaybackSpec Spec;

	if (Smoother != None && !Smoother.bDeleteMe)
		return;

	if (Viewport == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None)
		return;

	Smoother = Spec.Spawn(class'UTRecorderSmooth');
	if (Smoother == None)
		return;

	if (SmoothInterval > 0.0)
		Smoother.IRec = SmoothInterval;
	if (SmoothMovement > 0.0)
		Smoother.DPos = SmoothMovement;
	if (SmoothRotation > 0.0)
		Smoother.DRot = SmoothRotation;
	Log("UTRecorder: spawned recorder smoother" @ Smoother, 'UTRecorder');
}

// Sets PlaybackFrameRate to Rate, which turns on the spawn check.
exec function DemoFrames(float Rate)
{
	PlaybackFrameRate = Rate;
	Log("UTRecorder: locking playback to" @ Rate @ "rendered frames per demo second", 'UTRecorder');
}

// Sets RunStart to Start, which makes the spawn check wait for the demo to
// reach the timer reset at that demo time.
exec function TimerReset(float Start)
{
	RunStart = Start;
	Log("UTRecorder: waiting for the timer reset at demo time" @ Start, 'UTRecorder');
}

// Signals the spawn once the demo reaches RunStart when TimerReset set it.
// Otherwise signals it once the followed player is alive: at once if the
// first player seen is alive, else when a dead one reaches SpawnHealth.
// Signals it anyway when no player is followed or SpawnTimeout runs out.
function CheckSpawn(float Delta)
{
	local DemoPlaybackSpec Spec;
	local PlayerPawn Linked;

	if (bSignalledSpawn || Viewport == None || Viewport.Actor == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None || Spec.Driver == None)
		return;

	SincePlayback += Delta;
	Linked = Spec.PlayerLinked;

	if (RunStart > 0.0)
	{
		if (Spec.Driver.GetCurrentTime() >= RunStart)
			SignalSpawn("timer reset at demo time" @ Spec.Driver.GetCurrentTime());
		return;
	}

	if (Linked == None)
	{
		if (bSeenPlayer || (SpawnTimeout > 0.0 && SincePlayback >= SpawnTimeout))
			SignalSpawn("no player to follow");
		return;
	}

	if (!bSeenPlayer)
	{
		bSeenPlayer = True;
		bSeenDead = Linked.Health <= 0;
		if (!bSeenDead)
			SignalSpawn("already alive");
		return;
	}

	if (bSeenDead && Linked.Health >= SpawnHealth)
		SignalSpawn("health" @ Linked.Health);
	else if (SpawnTimeout > 0.0 && SincePlayback >= SpawnTimeout)
		SignalSpawn("no spawn within" @ SpawnTimeout @ "seconds");
}

// Logs the spawn mark and sets the demo speed to 1.
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

// Logs the start and end marks, and begins the exit once the demo has sat
// on its last frame for ExitDelay seconds.
function CheckPlayback(float Delta)
{
	local DemoPlaybackSpec Spec;
	local int Current, Total;

	if (Viewport == None || Viewport.Actor == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None)
	{
		CheckWithoutSpectator(Delta);
		return;
	}

	bSeenSpec = True;

	// The driver is set only once playback runs.
	if (Spec.Driver == None)
		return;

	LastStreamTime = Spec.Driver.GetCurrentTime();

	if (Rewinding(Spec, Delta))
		return;

	Total = Spec.Driver.GetTotalFrames();
	Current = Spec.Driver.GetCurrentFrame();

	if (!bMarkedStart && Total > 0 && Current > 0)
	{
		bMarkedStart = True;
		MarkTime("start");
	}

	if (Total <= 0)
		return;

	if (Current < Total - 1)
	{
		SinceEnd = 0.0; // seeking backwards counts as still playing
		return;
	}

	MarkEnd();
	SinceEnd += Delta;

	if (bVerbose)
		Log("UTRecorder: at end frame" @ Current $ "/" $ Total $ "," @ SinceEnd @ "of" @ ExitDelay, 'UTRecorder');

	if (SinceEnd >= ExitDelay)
		BeginExit("demo reached frame" @ Current @ "of" @ Total);
}

// Begins the exit ExitDelay seconds after a demo spectator that existed is
// gone, or once StartupTimeout runs out when none has appeared.
function CheckWithoutSpectator(float Delta)
{
	if (!bSeenSpec)
	{
		if (StartupTimeout > 0.0 && Elapsed >= StartupTimeout)
			BeginExit("no demo spectator after" @ StartupTimeout @ "seconds");
		return;
	}

	MarkEnd();
	SinceEnd += Delta;
	if (SinceEnd >= ExitDelay)
		BeginExit("demo spectator gone");
}

// Logs Reason, runs "stopdemo" when bStopDemoFirst is on, and leaves "exit"
// to a following tick.
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
