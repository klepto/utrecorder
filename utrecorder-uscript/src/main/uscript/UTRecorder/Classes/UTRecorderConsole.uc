//=============================================================================
// UTRecorderConsole - console that drives unattended demo playback and quits
// the game once the demo ends.
//
// Configured through its section of the game ini and installed as the
// [Engine.Engine] Console. While bAutoExit is on, the console logs a start
// and an end mark around the demo, then stops playback and exits the process
// once the demo passes the end TimerStop set, the demo is over, the demo
// spectator is gone, or a timeout runs out. While bAutoExit is on, it also
// stops warp zones from moving actors on the client.
//
// Exec commands, typically run from an -EXEC script. They act whether
// bAutoExit is on or not:
//   DisableRendering  stop drawing the world,
//   HideMessages      keep chat and progress messages off the HUD,
//   SeekStart         pause the demo until ticks settle, then seek to
//                     SEEK_PREROLL seconds before the run start TimerReset
//                     set, or to the demo start without one, without arming
//                     the hook library,
//   DemoFrames <rate> turn on the spawn check when the rate is above 0: set
//                     the demo speed to 1 once the followed player spawns,
//                     which arms the hook library, and log a spawn mark on
//                     the next tick,
//   TimerReset <demo time>
//                     make DemoFrames signal the spawn instead once the demo
//                     reaches the demo time the Bunny Track timer of the run
//                     resets at, or at once when that demo time is 0 or
//                     earlier, since the run is then already under way at
//                     the demo's first frame,
//   TimerStop <demo time>
//                     end the run TIMER_END_DELAY seconds of demo time after
//                     the demo time the Bunny Track timer of the run stops at,
//   SmoothRecorder    smooth the followed player's movement and view with a
//                     UTRecorderSmooth.
//=============================================================================
class UTRecorderConsole extends UTConsole
	config;

const SHOW_PLAYERCTRL = 0x00000800;
const SHOW_REALTIME = 0x00004000;

// A tick shorter than this counts as settled for SeekStart.
const SETTLED_DELTA = 0.05;
// Settled ticks in a row SeekStart waits for before seeking.
const SETTLED_TICKS = 10;
// Seconds of demo time SeekStart lands before the run start.
const SEEK_PREROLL = 2.0;
// Seconds of demo time a seek must move to be issued, as in udemo's GotoFrame.
const SEEK_MIN_DISTANCE = 0.5;
// Seconds of demo time the end mark follows the stop of the Bunny Track timer.
const TIMER_END_DELAY = 1.0;

// Turns on the marks, the exit and the warp-zone unlinking.
// Off by default.
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

// The rate DemoFrames set. Above 0, the console watches for the followed
// player's spawn. No other use is made of the value.
var config float PlaybackFrameRate;

// Health at which a dead followed player counts as spawned.
var config int SpawnHealth;

// Seconds of playback after which the spawn is signalled anyway. 0 disables.
var config float SpawnTimeout;

var bool bNoRendering;    // DisableRendering was run
var bool bHideMessages;   // HideMessages was run
var bool bSignalledSpawn; // the spawn has been signalled
var bool bSpawnPending;   // the spawn mark is due at the start of the next tick
var bool bSeenPlayer;     // a followed player has existed at some point
var bool bSeenDead;       // the first followed player seen was dead
var float SincePlayback;  // seconds of playback the spawn check has seen
var bool bRunStart;       // TimerReset was run, so RunStart is known
var float RunStart;       // demo time TimerReset set, 0 or earlier when the run began before the demo
var float LastStreamTime; // demo time of the last tick that had a driver
var int RenderedFrames;   // ticks counted while bAutoExit is on
var bool bReachedStart;   // the start seek is over and the demo is past its first frame
var bool bMarkedEnd;      // the end mark has been logged
var bool bSeekStart;      // seek to the run start once ticks settle
var bool bHeldForSeek;    // the demo has been paused until ticks settle
var bool bSought;         // the start seek has been issued
var int SettledTicks;     // settled ticks in a row before the start seek
var float TimerEnd;       // demo time the end mark is due at, 0 when TimerStop was not run
var bool bSmoothRecorder; // SmoothRecorder was run
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

// Applies the exec commands that were run and runs the start seek, then polls
// playback and advances the exit while bAutoExit is on.
event Tick(float Delta)
{
	if (bSpawnPending)
	{
		bSpawnPending = False;
		MarkTime("spawn");
	}

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
	{
		CheckStart(Delta);
		return;
	}

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
// Keeps the HUD of Linked when the spawn fails, so the next tick tries again.
function MuteHUD(DemoPlaybackSpec Spec, PlayerPawn Linked)
{
	local UTRecorderHUD HUD;

	HUD = Spec.Spawn(class'UTRecorderHUD', Linked);
	if (HUD == None)
		return;

	HUD.Real = Spec;
	Linked.myHUD.Destroy();
	Linked.myHUD = HUD;
	if (Linked.PlayerReplicationInfo != None)
		Log("UTRecorder: muted the HUD of" @ Linked.PlayerReplicationInfo.PlayerName, 'UTRecorder');
	else
		Log("UTRecorder: muted the HUD of" @ Linked, 'UTRecorder');
}

// Pauses the demo until ticks settle, then seeks to SEEK_PREROLL seconds
// before the run start, or to the demo start without one, before the demo
// reaches the start.
exec function SeekStart()
{
	bSeekStart = True;
	Log("UTRecorder: seeking to the run start once ticks settle", 'UTRecorder');
}

// True while the seek SeekStart asked for is pending or still seeking.
// Pauses the demo on the first call. Resumes it and issues the seek once
// SETTLED_TICKS ticks in a row are shorter than SETTLED_DELTA.
function bool SeekingStart(DemoPlaybackSpec Spec, float Delta)
{
	if (!bSeekStart)
		return False;

	if (bSought)
		return Spec.SeekTick > 0 || Spec.bSeeking;

	if (!bHeldForSeek)
	{
		bHeldForSeek = True;
		Log("UTRecorder: pausing the demo at demo time" @ Spec.Driver.GetCurrentTime() @ "until ticks settle", 'UTRecorder');
		Spec.Driver.PauseDemo(True);
	}

	if (Delta < SETTLED_DELTA)
		SettledTicks++;
	else
		SettledTicks = 0;

	if (SettledTicks < SETTLED_TICKS)
		return True;

	bSought = True;
	Spec.Driver.PauseDemo(False);
	Log("UTRecorder: resumed the demo at demo time" @ Spec.Driver.GetCurrentTime(), 'UTRecorder');
	SeekWithoutArming(Spec, FMax(0.0, RunStart - SEEK_PREROLL));
	return Spec.SeekTick > 0 || Spec.bSeeking;
}

// Seeks the demo to Target seconds through the jump path of udemo's
// GotoFrame, without its start-time guard, its short forward ReadCache path
// and its closing SetSpeed. Sets SeekTick and bFixLevelTime as the spectator
// does after its own GotoFrame.
function SeekWithoutArming(DemoPlaybackSpec Spec, float Target)
{
	local DemoInterface Driver;
	local float Current, Time;

	Driver = Spec.Driver;
	Current = Driver.GetCurrentTime();
	if (Target > Driver.GetTotalTime() || Abs(Target - Current) < SEEK_MIN_DISTANCE)
		return;

	Log("UTRecorder: seeking from" @ Current @ "to" @ Target, 'UTRecorder');
	Spec.bSeeking = True;
	Time = Target + Driver.GetStartTime();
	if (Time < Current)
	{
		Spec.BackUpRefs();
		Driver.JumpBack();
	}
	if (Time - class'DemoSettings'.default.CacheSeconds > Driver.GetCurrentTime())
		Driver.ReadTo(Time - class'DemoSettings'.default.CacheSeconds);
	Spec.FixGRI(Time - Current);
	Driver.ReadCache(Time, class'DemoSettings'.default.TickSize);
	Spec.bSeeking = False;
	Spec.SeekTick = 3;
	Driver.bFixLevelTime = True;
}

// Keeps a UTRecorderSmooth spawned in the demo level from the next tick on.
exec function SmoothRecorder()
{
	bSmoothRecorder = True;
	Log("UTRecorder: smoothing the followed player", 'UTRecorder');
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

	Log("UTRecorder: spawned recorder smoother" @ Smoother, 'UTRecorder');
}

// Sets PlaybackFrameRate to Rate, which turns on the spawn check when Rate is
// above 0. Does not change the playback rate.
exec function DemoFrames(float Rate)
{
	PlaybackFrameRate = Rate;
	if (Rate > 0)
		Log("UTRecorder: watching for the spawn at rate" @ Rate, 'UTRecorder');
	else
		Log("UTRecorder: not watching for the spawn at rate" @ Rate, 'UTRecorder');
}

// Sets RunStart to Start, which makes the spawn check wait for the demo to
// reach the timer reset at that demo time. A Start of 0 or earlier means the
// run is already under way at the demo's first frame.
exec function TimerReset(float Start)
{
	bRunStart = True;
	RunStart = Start;
	Log("UTRecorder: waiting for the timer reset at demo time" @ Start, 'UTRecorder');
}

// Sets TimerEnd TIMER_END_DELAY seconds of demo time after Stop, the demo
// time the Bunny Track timer of the run stops at, which makes CheckPlayback
// end the run there.
exec function TimerStop(float Stop)
{
	TimerEnd = Stop + TIMER_END_DELAY;
	Log("UTRecorder: ending the run at demo time" @ TimerEnd @ "after the timer stop at demo time" @ Stop, 'UTRecorder');
}

// Signals the spawn once the demo reaches RunStart when TimerReset set it,
// at once when RunStart is 0 or earlier.
// Otherwise signals it once the followed player is alive: at once if the
// first player seen is alive, else when a dead one reaches SpawnHealth.
// Signals it anyway when a followed player seen before is gone, or when
// SpawnTimeout runs out. While SeekStart is on, waits for the demo to reach
// the start first.
function CheckSpawn(float Delta)
{
	local DemoPlaybackSpec Spec;
	local PlayerPawn Linked;

	if (bSignalledSpawn || Viewport == None || Viewport.Actor == None)
		return;

	if (bSeekStart && !bReachedStart)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None || Spec.Driver == None)
		return;

	SincePlayback += Delta;
	Linked = Spec.PlayerLinked;

	if (bRunStart)
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

// Sets the demo speed to 1, which arms the hook library, and puts the spawn
// mark off to the start of the next tick.
function SignalSpawn(string Reason)
{
	local DemoPlaybackSpec Spec;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None || Spec.Driver == None)
		return;

	bSignalledSpawn = True;
	bSpawnPending = True;
	Log("UTRecorder: spawn -" @ Reason, 'UTRecorder');
	Spec.Driver.SetSpeed(1.0);
}

// Logs the start and end marks. Begins the exit ExitDelay seconds after the
// demo passes TimerEnd once the demo reached the start, or once the demo has sat
// on its last frame for ExitDelay seconds, whichever comes first.
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

	if (!ReachStart(Spec, Delta))
		return;

	Total = Spec.Driver.GetTotalFrames();
	Current = Spec.Driver.GetCurrentFrame();

	if (bReachedStart && TimerEnd > 0.0 && LastStreamTime >= TimerEnd)
	{
		MarkEnd();
		SinceEnd += Delta;
		if (SinceEnd >= ExitDelay)
			BeginExit("Bunny Track timer stopped at demo time" @ TimerEnd - TIMER_END_DELAY);
		return;
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

// Runs the start seek and watches for the start while bAutoExit is off, so
// that the spawn check still sees the demo reach the start.
function CheckStart(float Delta)
{
	local DemoPlaybackSpec Spec;

	if (Viewport == None)
		return;

	Spec = DemoPlaybackSpec(Viewport.Actor);
	if (Spec == None || Spec.Driver == None)
		return;

	ReachStart(Spec, Delta);
}

// False while the seek SeekStart asked for is pending or still seeking.
// Otherwise sets bReachedStart once the demo is past its first frame, and
// logs the start mark then while bAutoExit is on.
function bool ReachStart(DemoPlaybackSpec Spec, float Delta)
{
	if (SeekingStart(Spec, Delta))
		return False;

	if (bReachedStart || Spec.Driver.GetTotalFrames() <= 0 || Spec.Driver.GetCurrentFrame() <= 0)
		return True;

	bReachedStart = True;
	if (bAutoExit)
		MarkTime("start");
	return True;
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
