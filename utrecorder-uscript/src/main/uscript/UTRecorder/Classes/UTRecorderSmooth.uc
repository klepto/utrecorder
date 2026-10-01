//=============================================================================
// UTRecorderSmooth - smooths the movement and view of the player a demo was
// recorded by, udemo's PlayerLinked, during playback.
//
// Ported from DemoSmoother of DemoSmoothing by Berserker_BG, used with his
// permission, with its velocity-aided smoothing two demo frames behind. The
// menu and the settings are left out; every part of the smoothing is always
// on.
//
// Every frame it keeps the followed player's snapshots with the demo time and
// the velocity each was taken at. His position follows the path his velocity
// traces between the snapshots, and a weighted fit of how far the snapshots
// sit from that path takes out the rounding of each position to a whole unit.
// His view follows a weighted curve through the view snapshots. Both are read
// at one moment, which runs two demo frames behind the demo. The smoother
// measures the demo's frame interval itself. No moment before the start of
// the timeline or before the latest teleport or respawn is shown: the player
// holds there until the delay has passed.
//
// It writes the pose onto the player, along with his eye height state, a
// level view roll and the flags of UTPure-style and IG+ players. The next
// demo frame overwrites the pose. While a smoother exists, udemo's own
// recorder smoothing is off.
//=============================================================================
class UTRecorderSmooth extends Actor;

// How many demo frames behind the demo the player is shown.
const FRAMES_BEHIND = 2.0;

var DemoPlaybackSpec     Spec;     // udemo's playback spectator
var PlayerPawn           Rec;      // the followed player (udemo's PlayerLinked)
var UTRecorderSmoothLate Late;     // does the work every frame, after the player (see Tick)
var PlayerPawn           LateFor;  // the player Late was spawned for
var int                  LateIdle; // frames since Late last did the work
var bool                 bVRPawn;  // the player draws first person from its own view copy (see ReadFrameValues)
var bool                 bOwnEye;  // it keeps IG+'s eye height model (see CalmOwnEye)

// ---- snapshots ------------------------------------------------------------
// Rings of 256, newest at the head. Times are demo seconds since T0. A new
// entry is taken when the demo changes a value, and a repeat of the last one
// when it has held still for a while (see TakeSamples).
var float PT[256], PX[256], PY[256], PZ[256];
var float PE[256];       // eye height, taken with the position
var float PES[256];      // the steady eye height of the snapshot (see SteadyEye)
var int   PSeg[256];     // X/Y piece: changes at a teleport
var int   PSegZ[256];    // Z piece: also changes at a step, a take-off, a landing
var int   PSegC[256];    // camera height piece: a teleport, a take-off, a landing - not a step
var byte  PAir[256];     // 1: the snapshot's camera height piece is in the air
var Mover PMov[256];     // the mover he stood on, if any (see MoverCarry)
var float PMX[256], PMY[256], PMZ[256];   // where that mover was then
var float PLX[256], PLY[256], PLZ[256];   // first snapshot off a mover: where it was then
var int   PHead, PCount;
// Rotation, unwrapped so a turn past 360 degrees stays continuous: view yaw and
// pitch, body yaw and pitch.
var float RT[256], RVY[256], RVP[256], RBY[256], RBP[256];
var int   RSeg[256];
var int   RHead, RCount;

// ---- the velocity path -------------------------------------------------------
// Per snapshot: his velocity in units per demo second, and the path that
// velocity traces, relative to the mover under him. The path's value is kept
// at PMid, halfway back to the previous snapshot - or at the snapshot itself
// where a piece starts. Three channels, each with pieces of its own: X/Y
// (bit 1), Z (bit 2) and camera height (bit 4).
var float PVX[256], PVY[256], PVZ[256];
var float PMid[256];
var float PDX[256], PDY[256], PDZ[256], PDC[256];
var int   VelXY[256], VelZ[256], VelC[256];   // the snapshot's piece per channel
var byte  VelNew[256];                        // the channels whose piece starts at the snapshot
var int   VelXYN, VelZN, VelCN;               // the newest piece per channel

// ---- what the demo last delivered (the player may be showing ours) --------
var bool    bHaveRawLoc, bRawAir, bHaveRawRot, bHaveRawEye, bHaveRawVel;
var vector  RawLoc;
var int     RawVP, RawVY, RawBP, RawBY;
var float   UVY, UVP, UBY, UBP;          // running unwrapped rotation
var float   RawEye;
var vector  RawVel;                      // in units per demo second
var Mover   RawMover;                    // under him at the newest demo frame
// The mover under him at the end of last frame, and where it was then.
var Mover   TrackMover;
var vector  TrackLoc;
var float   LastPT, LastRT;              // time of the newest entry per ring
var float   ZMovedT;                     // when his height last changed
var int     Seg, SegZ, SegC, RSegN;
var bool    bHaveCut;                    // a teleport or respawn is not snapped to yet
var float   CutT;                        // the time of its first snapshot
// Demo seconds per game second, the last time the driver showed it (see
// GameSpeed).
var float   VelScale;
var bool    bHaveVelScale;

// ---- what we wrote last frame --------------------------------------------
var bool    bWroteLoc, bWroteRot, bWroteEye;
var vector  WLoc;
var int     WVP, WVY, WBP, WBY;
var float   WEye;

// ---- the clock ------------------------------------------------------------
// ClockAcc adds up each frame's demo advance. The demo's own clock is ClockAcc
// plus an offset, estimated as the highest lower bound of the last quarter to
// half second.
var bool    bPrimed;
var float   T0, ClockAcc, Off, OffEst;
var float   ClockComp;       // what rounding took from ClockAcc, given back next frame
var float   CurMax, PrevMax, BucketAcc;
var int     LastFrame;
var float   LastSpt;
var float   TauP, TauR;      // the demo moments shown now: position, rotation
var bool    bHaveTau;
// The guard against a demo advance the frame's time step does not show (see
// GuardRate).
var float   RateSumA, RateSumS, RateFix, RatePend, RateLast, RateSpeed;
var byte    RateMode;
// The level clock, for the frames whose time step the level clamped (see
// LevelStep).
var float   LastLT, SmDLT;
var bool    bHaveLT;

// ---- the delay --------------------------------------------------------------
// The demo's frame interval, measured over its first 32 reads (see
// MeasureInterval), and the delay the moments shown run behind the demo.
var float   IRecBuf[32];
var int     IRecN;
var float   IRec;
var bool    bDelaySet;
var float   DCur;            // the delay in use, eased towards Delay()
var bool    bDCur;
var float   ShowFrom;        // no moment before it is shown: the timeline's start, or the latest cut
var float   FAdv;            // this frame's demo advance

var bool    bNativeOff;      // udemo's recorder smoothing is off because of us

// ---- this frame -------------------------------------------------------------
var vector  FLoc;            // the player's position before anything is written
var vector  FVel;            // the player's velocity, in units per game second
var vector  FTickCarry;      // how far a mover carried him this frame (taken off FLoc)
var rotator FView, FBody;
var float   FEye;
var float   FBaseEye;        // BaseEyeHeight: udemo's copy of the eye height at the start of the frame
var int     FVR;             // the player's own view copy: 0 it keeps none, 1 no new one this frame, 2 a new one
var bool    bFPrime;         // this frame starts the timeline over
var bool    bFLocFresh, bFRotFresh, bFEyeFresh;
var float   StarveAdv;       // demo time read while held at the newest snapshot
var float   FStep;           // demo time read this frame
var float   FNow;            // the demo's clock this frame
var float   FTarget;         // the eye height the steady eye height moves towards (see SteadyTarget)
var bool    bFTargetDemo;    // FTarget is the demo's own eye height: he crouches
// the landing near the moment shown, this frame (see LandCheck)
var bool    bFLand;
var int     LandG, LandSegC;
var float   LandR, LandEnd, LandW;

// =============================================================================
// Every frame
// =============================================================================

// Keeps a UTRecorderSmoothLate owned by the followed player, which runs the
// frame after the player's own update. A new one is spawned for each player
// this follows, and one that goes three frames without an update is replaced
// by one owned by nothing. Runs the frame from here only if none can be
// spawned.
event Tick(float Delta)
{
	local PlayerPawn P;
	local UTRecorderSmoothLate Old;

	P = LinkedRecorder();
	if (Late != None && !Late.bDeleteMe && Late.Owner != None && Late.Owner.bDeleteMe)
		Late.SetOwner(None);
	if (Late == None || Late.bDeleteMe || (P != None && P != LateFor) || LateIdle > 2)
	{
		Old = Late;
		if (LateIdle > 2 && P == LateFor)
			Late = Spawn(class'UTRecorderSmoothLate');
		else
			Late = Spawn(class'UTRecorderSmoothLate', P);
		if (Late != None)
		{
			Late.Smooth = self;
			LateFor = P;
		}
		if (Old != None && !Old.bDeleteMe && Old != Late)
			Old.Destroy();
		LateIdle = 0;
	}
	if (Late == None)
		RunFrame(Delta);
	else
		LateIdle++;
}

// Reads the demo and places the followed player for this frame. Starts the
// timeline over while udemo seeks.
function RunFrame(float Delta)
{
	if (!FindRecorder())
	{
		Release();
		if (bPrimed)
			ResetHistory();
		TrackMover = None;
		return;
	}
	ServiceNative();
	if (Spec.bSeeking || Spec.SeekTick > 0)
	{
		RestoreValues();
		ResetHistory();
		TrackMover = None;
		return;
	}
	Observe(Delta);
	Place();
	// Lowers a UTPure-style player's view copy flag, so it draws the view
	// written here.
	if (bVRPawn)
	{
		Rec.SetPropertyText("zzbRepVRData", "False");
		if (bOwnEye)
			CalmOwnEye();
	}
	TrackMover = Mover(Rec.Base);
	if (TrackMover != None)
		TrackLoc = TrackMover.Location;
}

// Finds udemo's spectator and the player it is linked to. A different player
// voids everything known.
function bool FindRecorder()
{
	local PlayerPawn P;

	if (!FindSpec())
		return false;
	P = LinkedRecorder();
	if (P != Rec)
	{
		RestoreValues();
		ResetHistory();
		Rec = P;
	}
	if (Rec == None)
		return false;
	bVRPawn = Rec.GetPropertyText("zzbRepVRData") != "";
	bOwnEye = bVRPawn && Rec.GetPropertyText("EyeHeightOffset") != "";
	return true;
}

// Finds udemo's spectator, and returns whether its demo driver is set.
function bool FindSpec()
{
	local DemoPlaybackSpec S;

	if (Spec == None || Spec.bDeleteMe)
	{
		Spec = None;
		foreach AllActors(class'DemoPlaybackSpec', S)
		{
			Spec = S;
			break;
		}
	}
	return Spec != None && Spec.Driver != None;
}

// The player udemo's spectator is linked to, while it exists.
function PlayerPawn LinkedRecorder()
{
	if (!FindSpec() || Spec.PlayerLinked == None || Spec.PlayerLinked.bDeleteMe)
		return None;
	return Spec.PlayerLinked;
}

// Switches udemo's own recorder smoothing off through the driver's live value,
// never the saved setting.
function ServiceNative()
{
	if (Spec.Driver.GetPropertyText("SmoothRecorderMovement") != "Smooth_None")
	{
		Spec.Driver.SetPropertyText("SmoothRecorderMovement", "Smooth_None");
		bNativeOff = true;
	}
}

// Puts udemo's own recorder smoothing back to its saved setting.
function RestoreNative()
{
	if (bNativeOff && Spec != None && Spec.Driver != None)
		Spec.Driver.SmoothRecorderMovement = class'DemoSettings'.default.SmoothRecorderMovement;
	bNativeOff = false;
}

// Stops writing, and puts udemo's own recorder smoothing back.
function Release()
{
	RestoreValues();
	if (Spec != None && Spec.Driver != None)
		RestoreNative();
}

// Puts the demo's own values back on whatever still shows ours.
function RestoreValues()
{
	local rotator R;
	local bool bOldCW;

	if (Rec != None && !Rec.bDeleteMe)
	{
		if (bWroteLoc && bHaveRawLoc && Rec.Location == WLoc)
		{
			bOldCW = Rec.bCollideWorld;
			Rec.bCollideWorld = false;
			Rec.SetLocation(RawLoc);
			Rec.bCollideWorld = bOldCW;
		}
		if (bWroteEye && bHaveRawEye && Rec.EyeHeight == WEye)
		{
			Rec.EyeHeight = RawEye;
			Rec.BaseEyeHeight = RawEye;
		}
		if (bWroteRot && bHaveRawRot)
		{
			if (Rec.ViewRotation.Pitch == WVP && Rec.ViewRotation.Yaw == WVY)
			{
				R = Rec.ViewRotation;
				R.Pitch = RawVP;
				R.Yaw = RawVY;
				Rec.ViewRotation = R;
			}
			if (Rec.Rotation.Pitch == WBP && Rec.Rotation.Yaw == WBY)
			{
				R = Rec.Rotation;
				R.Pitch = RawBP;
				R.Yaw = RawBY;
				Rec.SetRotation(R);
			}
		}
		if (bWroteLoc || bWroteRot)
			FollowTrailers();
	}
	ForgetWrites();
}

function ForgetWrites()
{
	bWroteLoc = false;
	bWroteRot = false;
	bWroteEye = false;
}

// Forgets everything that describes the demo's timeline. The demo's frame
// interval and the delay are kept.
function ResetHistory()
{
	ForgetWrites();
	PHead = -1;
	PCount = 0;
	RHead = -1;
	RCount = 0;
	bHaveRawLoc = false;
	bHaveRawRot = false;
	bHaveRawEye = false;
	bHaveRawVel = false;
	RawMover = None;
	TrackMover = None;
	Seg = 0;
	SegZ = 0;
	SegC = 0;
	RSegN = 0;
	bHaveCut = false;
	bPrimed = false;
	bHaveTau = false;
	ClockAcc = 0.0;
	Off = 0.0;
	OffEst = 0.0;
	StarveAdv = 0.0;
	RateSumA = 0.0;
	RateSumS = 0.0;
	RatePend = 0.0;
	RateLast = 0.0;
	ClockComp = 0.0;
	bHaveLT = false;
}

// =============================================================================
// Reading the demo
// =============================================================================

// Advances the clock by this frame's demo advance and takes the snapshots of
// the demo frames read this frame.
function Observe(float Delta)
{
	local float Spt, St, Adv, LB, Step;
	local int Frame, NFr;
	local bool bNew, bPrimeNow;

	Spt = Spec.Driver.GetCurrentTime();
	Frame = Spec.Driver.GetCurrentFrame();
	// A jump the seek flags did not show: a backward demo frame, or far more
	// demo time than one frame can hold.
	if (bPrimed && (Frame < LastFrame || Spt < LastSpt - 0.001 || Spt > LastSpt + 1.0))
	{
		RestoreValues();
		ResetHistory();
	}
	if (!bPrimed)
	{
		bPrimed = true;
		// whole seconds, so moving it on (ShiftTimes) is exact
		T0 = float(int(Spt));
		LastFrame = Frame;
		LastSpt = Spt;
		bNew = true;
		bPrimeNow = true;
		NFr = 0;
	}
	else
	{
		bNew = (Frame != LastFrame);
		NFr = Frame - LastFrame;
	}
	St = Spt - T0;
	bFPrime = bPrimeNow;
	FStep = 0.0;
	if (bNew && Spt > LastSpt)
		FStep = FMin(Spt - LastSpt, 0.1);

	// ---- the clock
	Step = LevelStep(Delta);
	if (Level.Pauser != "" || Spec.Driver.IsPaused() != 0)
		Adv = 0.0;
	else if (Spec.Driver.PlayBackMode == 2)
		Adv = FMax(Spt - LastSpt, 0.0);   // "as fast as possible" reads one demo frame per frame
	else
		Adv = Step * Spec.Driver.mySpeed / FMax(Level.TimeDilation, 0.000001);
	GuardRate(Adv, Spt, bNew);
	Adv *= RateFix;
	AddClock(Adv);
	FAdv = Adv;
	LB = St - ClockAcc;
	if (bPrimeNow)
	{
		CurMax = LB;
		PrevMax = LB;
		BucketAcc = 0.0;
		Off = LB;
		ShowFrom = St;
	}
	CurMax = FMax(CurMax, LB);
	BucketAcc += Adv;
	if (BucketAcc >= 0.25)
	{
		PrevMax = CurMax;
		CurMax = LB;
		BucketAcc = 0.0;
	}
	OffEst = FMax(PrevMax, CurMax);
	// The offset in use follows the estimate at a rate proportional to the gap.
	Off += (OffEst - Off) * FMin(1.0, 2.0 * Adv);

	if (bNew)
	{
		MeasureInterval(Spt - LastSpt, NFr);
		TakeSamples(St, NFr);
	}
	else
		ReadFrameValues();
	LastFrame = Frame;
	LastSpt = Spt;
	if (ClockAcc > 300.0)
		ShiftTimes(256.0);
}

// ClockAcc += Adv, with the rounding carried to the next frame.
function AddClock(float Adv)
{
	local float Y, Sum;

	Y = Adv - ClockComp;
	Sum = ClockAcc + Y;
	ClockComp = (Sum - ClockAcc) - Y;
	ClockAcc = Sum;
}

// The time step this frame really had, dilation included: Delta, unless the
// level clamped it to 0.0005..0.4 s - then the level clock's own step,
// averaged over about ten frames at the low end. Called every frame, so the
// level clock is always tracked.
function float LevelStep(float Delta)
{
	local float DLT;

	DLT = Level.TimeSeconds - LastLT;
	LastLT = Level.TimeSeconds;
	if (!bHaveLT || DLT < 0.0 || DLT > 5.0)
	{
		// first frame, or udemo moved the level clock (a seek does)
		bHaveLT = true;
		SmDLT = Delta;
		return Delta;
	}
	SmDLT += (FMin(DLT, 0.05) - SmDLT) * 0.1;
	if (Delta <= 0.0005001)
		return FMin(SmDLT, 0.0005);
	if (Delta >= 0.3999)
		return FMax(DLT, Delta);
	return Delta;
}

// Moves T0 on by By and every time back by By, which changes nothing else.
function ShiftTimes(float By)
{
	local int k, i;

	T0 += By;
	ClockAcc -= By;
	LastPT -= By;
	LastRT -= By;
	ZMovedT -= By;
	CutT -= By;
	ShowFrom -= By;
	TauP -= By;
	TauR -= By;
	for (k = 0; k < PCount; k++)
	{
		i = (PHead - k) & 255;
		PT[i] -= By;
		PMid[i] -= By;
	}
	for (k = 0; k < RCount; k++)
	{
		i = (RHead - k) & 255;
		RT[i] -= By;
	}
}

// Compares the demo's own advance with the clock's over three-second
// stretches, and scales the clock by their ratio after two stretches in a row
// differ by more than 3% and agree within 10%, until one comes within 1.5%. A
// read after a gap is left out of both sums.
function GuardRate(float Adv, float Spt, bool bNew)
{
	local float Ratio, Step;

	if (RateFix <= 0.0)
		RateFix = 1.0;
	// a new speed or playback mode is a new relation between the two clocks
	if (Spec.Driver.mySpeed != RateSpeed || Spec.Driver.PlayBackMode != RateMode)
	{
		RateSpeed = Spec.Driver.mySpeed;
		RateMode = Spec.Driver.PlayBackMode;
		RateFix = 1.0;
		RateLast = 0.0;
		RateSumA = 0.0;
		RateSumS = 0.0;
		RatePend = 0.0;
	}
	RatePend += Adv;
	if (!bNew)
		return;
	Step = Spt - LastSpt;
	if (Step > 0.0 && Step < 2.0 * RatePend + 0.05 && RatePend < 2.0 * Step + 0.05)
	{
		RateSumA += RatePend;
		RateSumS += Step;
	}
	RatePend = 0.0;
	if (RateSumA < 3.0)
		return;
	Ratio = RateSumS / RateSumA;
	RateSumA = 0.0;
	RateSumS = 0.0;
	if (Abs(Ratio - 1.0) < 0.015 || Ratio < 0.25 || Ratio > 4.0)
	{
		RateLast = 0.0;
		RateFix = 1.0;
		return;
	}
	if (RateFix != 1.0)
		RateFix = Ratio;
	else if (Abs(Ratio - 1.0) > 0.03 && RateLast > 0.0 && Abs(Ratio / RateLast - 1.0) < 0.1)
		RateFix = Ratio;
	RateLast = Ratio;
}

// Takes the demo's frame interval as the median of the demo time per demo
// frame over the first 32 reads that took in fewer than 50 demo frames and
// no more than a tenth of a second each. Measured once for the whole
// playback.
function MeasureInterval(float DT, int NFr)
{
	local float E, Tmp;
	local int i, j;

	if (bDelaySet || NFr <= 0 || NFr >= 50 || DT <= 0.0)
		return;
	E = DT / NFr;
	if (E > 0.1)
		return;
	IRecBuf[IRecN] = E;
	IRecN++;
	if (IRecN < 32)
		return;
	for (i = 1; i < 32; i++)
	{
		Tmp = IRecBuf[i];
		j = i - 1;
		while (j >= 0 && IRecBuf[j] > Tmp)
		{
			IRecBuf[j + 1] = IRecBuf[j];
			j--;
		}
		IRecBuf[j + 1] = Tmp;
	}
	IRec = (IRecBuf[15] + IRecBuf[16]) * 0.5;
	bDelaySet = true;
	Log("UTRecorder: recorder smoother measured a demo frame interval of" @ IRec @ "s", 'UTRecorder');
}

// The demo's frame interval: 8 ms until it is measured.
function float Interval()
{
	if (bDelaySet)
		return IRec;
	return 0.008;
}

// How far behind the demo the moments shown run: FRAMES_BEHIND demo frames,
// and 30 ms until the frame interval is measured.
function float Delay()
{
	if (bDelaySet)
		return FRAMES_BEHIND * IRec;
	return FMax(FRAMES_BEHIND * 0.008, 0.03);
}

// Reads this frame's values of the player, and which of them the demo
// delivered - a value equal to what we wrote last frame is ours. Carried by a
// mover, ours comes back moved by the carry, and taking the carry off again
// can differ from it in the last digits - hence the twentieth of a unit there.
// The demo's own position is in whole units, so a position in whole units is
// the demo's even then.
function ReadFrameValues()
{
	FTickCarry = vect(0,0,0);
	if (TrackMover != None && Rec.Base == TrackMover && !TrackMover.bDeleteMe)
		FTickCarry = TrackMover.Location - TrackLoc;
	FLoc = Rec.Location - FTickCarry;
	FVel = Rec.Velocity;
	FView = Rec.ViewRotation;
	FBody = Rec.Rotation;
	FEye = Rec.EyeHeight;
	FBaseEye = Rec.BaseEyeHeight;
	FVR = 0;
	if (bVRPawn)
	{
		// A UTPure-style player: the demo's own eye height, which lines up with
		// the position, is in udemo's copy from the start of the frame. On the
		// first frame after a seek udemo has not made that copy yet, and the
		// view copy is read instead - when there is one.
		FVR = 1;
		if (Rec.GetPropertyText("zzbRepVRData") ~= "True")
			FVR = 2;
		if (bFPrime && FVR == 2)
		{
			FEye = float(Rec.GetPropertyText("zzRepVREye"));
			FView.Yaw = int(Rec.GetPropertyText("zzRepVRYaw"));
			FView.Pitch = int(Rec.GetPropertyText("zzRepVRPitch"));
		}
		else
			FEye = FBaseEye;
	}
	bFLocFresh = !(bWroteLoc && (Rec.Location == WLoc
		|| (FTickCarry != vect(0,0,0) && VSize(FLoc - WLoc) < 0.05 && !WholeUnits(FLoc))));
	bFRotFresh = !(bWroteRot && FView.Pitch == WVP && FView.Yaw == WVY && FBody.Pitch == WBP && FBody.Yaw == WBY);
	bFEyeFresh = !(bWroteEye && FEye == WEye);
}

// Whether every component of V is within a hundredth of a whole unit.
function bool WholeUnits(vector V)
{
	return OffWhole(V.X) < 0.01 && OffWhole(V.Y) < 0.01 && OffWhole(V.Z) < 0.01;
}

// How far F is from the nearest whole number.
function float OffWhole(float F)
{
	local float D;

	D = Abs(F - float(int(F)));
	return FMin(D, 1.0 - D);
}

// Takes the snapshots of a frame that read demo data. A changed value becomes
// a snapshot at the newest demo frame's time; an unchanged one is repeated
// once it has held for three demo frames. A read that took in several demo
// frames also gets the old values at the previous read, and the skipped
// frames on a straight line from there.
function TakeSamples(float St, int NFr)
{
	local bool bAir, bVFresh, bBFresh, bLocNew, bEyeNew, bHadLoc, bSplit, bCut;
	local float Dist, DT, Hold, DZ, DE, PrevT, OldEye, F, OldT, VE;
	local float OVY, OVP, OBY, OBP;
	local int DVY, DVP, DBY, DBP, NVP, NVY, NBP, NBY, k, N;
	local vector Own, MV, OldLoc, OldM, NewM, NewVel, OldVel;

	ReadFrameValues();
	bAir = (Rec.Base == None);
	RawMover = Mover(Rec.Base);
	Hold = FMax(3.0 * Interval(), 0.02);
	PrevT = LastSpt - T0;
	N = Min(NFr - 1, 8);
	NewVel = FVel * GameSpeed();
	OldVel = NewVel;
	if (bHaveRawVel)
		OldVel = RawVel;

	// A UTPure-style player's view copy holds the eye height its previous
	// frame ended with. Where the demo's own value for that frame holds the
	// standing eye height instead, the copy puts the snapshot right.
	if (FVR == 2 && !bFPrime && PCount > 0 && Abs(PT[PHead] - PrevT) < 0.0005)
	{
		VE = float(Rec.GetPropertyText("zzRepVREye"));
		if (Abs(VE - PE[PHead]) > 0.001
			&& (NFr == 1 || (PE[PHead] == Rec.default.BaseEyeHeight && Abs(VE - PE[PHead]) > 2.0)))
		{
			PE[PHead] = VE;
			RawEye = VE;
			FTarget = SteadyTarget(bRawAir, PT[PHead]);
			if (PCount > 1)
				PES[PHead] = SteadyEye((PHead - 1) & 255, PT[PHead], PZ[PHead]);
			else
				PES[PHead] = FTarget;
		}
	}

	// ---- position and eye height
	OldLoc = RawLoc;
	OldEye = RawEye;
	bHadLoc = bHaveRawLoc;
	bLocNew = bFLocFresh && (!bHaveRawLoc || FLoc != RawLoc);
	bEyeNew = bFEyeFresh && (!bHaveRawEye || FEye != RawEye);
	DE = 0.0;
	if (bEyeNew && bHaveRawEye)
		DE = FEye - RawEye;
	if (bEyeNew || !bHaveRawEye)
	{
		RawEye = FEye;
		bHaveRawEye = true;
	}
	if (bLocNew)
	{
		if (bHaveRawLoc)
		{
			Dist = VSize(FLoc - RawLoc);
			DT = FMax(St - LastPT, 0.0001);
			DZ = FLoc.Z - RawLoc.Z;
			// his own move, apart from what a mover under him did
			Own = FLoc - RawLoc;
			if (RawMover != None && PCount > 0 && PMov[PHead] == RawMover)
			{
				MV = MoverAt(RawMover, St);
				Own.X -= MV.X - PMX[PHead];
				Own.Y -= MV.Y - PMY[PHead];
				Own.Z -= MV.Z - PMZ[PHead];
			}
			if (Dist > 64.0 + 3000.0 * DT)
			{
				// teleporter, translocator, respawn: no smoothing across it
				Seg++;
				SegZ++;
				SegC++;
				RSegN++;
				bSplit = true;
				bCut = true;
				bHaveCut = true;
				CutT = St;
			}
			else if (bAir != bRawAir)
			{
				SegZ++;
				SegC++;
				bSplit = true;
			}
			else if (Abs(Own.Z) > 4.0
				&& (Abs(Own.Z) > 4.0 + VSize(Own * vect(1,1,0)) || PrevT - ZMovedT >= Interval())
				&& (!bAir || Abs(DE + DZ) < 0.5 * Abs(DZ)))
			{
				// A step: a rise steeper than any slope he can walk - or, when
				// the read covers so much ground that the slope cannot tell, a
				// rise out of ground that had been level. Taken on the ground,
				// or with the eye height giving most of it back.
				SegZ++;
				bSplit = true;
			}
			if (Abs(Own.Z) >= 0.5 || bSplit)
				ZMovedT = St;
		}
		else
			ZMovedT = St;
		RawLoc = FLoc;
		bRawAir = bAir;
		bHaveRawLoc = true;
	}
	FTarget = SteadyTarget(bAir, St);
	if (bLocNew || (bEyeNew && bHaveRawLoc) || (bHaveRawLoc && St - LastPT > Hold))
	{
		if (RawMover != None)
			NewM = MoverAt(RawMover, St);
		if ((bLocNew || bEyeNew) && bHadLoc && !bSplit && PCount > 0 && PMov[PHead] == RawMover
			&& PrevT >= LastPT && St - PrevT > 0.0005)
		{
			// the mover's own track, on a straight line from the last snapshot
			OldT = LastPT;
			OldM.X = PMX[PHead];
			OldM.Y = PMY[PHead];
			OldM.Z = PMZ[PHead];
			if (PrevT > OldT + 0.0005)
				PushP(PrevT, OldLoc, OldEye, OldM + (NewM - OldM) * ((PrevT - OldT) / (St - OldT)), OldVel);
			for (k = 1; k <= N; k++)
			{
				F = float(k) / float(N + 1);
				PushP(PrevT + (St - PrevT) * F, OldLoc + (RawLoc - OldLoc) * F, OldEye + (RawEye - OldEye) * F,
					OldM + (NewM - OldM) * ((PrevT + (St - PrevT) * F - OldT) / (St - OldT)), OldVel + (NewVel - OldVel) * F);
			}
		}
		PushP(St, RawLoc, RawEye, NewM, NewVel);
	}
	RawVel = NewVel;
	bHaveRawVel = true;

	// ---- rotation: view and body, each the demo's when it is not ours
	bVFresh = !(bWroteRot && FView.Pitch == WVP && FView.Yaw == WVY);
	bBFresh = !(bWroteRot && FBody.Pitch == WBP && FBody.Yaw == WBY);
	// a UTPure-style player's view is its view copy's: no new copy, no new view
	if (FVR == 1)
		bVFresh = false;
	if (!bHaveRawRot)
	{
		RawVP = FView.Pitch & 65535;
		RawVY = FView.Yaw & 65535;
		RawBP = FBody.Pitch & 65535;
		RawBY = FBody.Yaw & 65535;
		UVP = RawVP;
		UVY = RawVY;
		UBP = RawBP;
		UBY = RawBY;
		bHaveRawRot = true;
		PushR(St, UVY, UVP, UBY, UBP);
		return;
	}
	NVP = RawVP;
	NVY = RawVY;
	NBP = RawBP;
	NBY = RawBY;
	if (bVFresh)
	{
		NVP = FView.Pitch & 65535;
		NVY = FView.Yaw & 65535;
	}
	if (bBFresh)
	{
		NBP = FBody.Pitch & 65535;
		NBY = FBody.Yaw & 65535;
	}
	if (NVP != RawVP || NVY != RawVY || NBP != RawBP || NBY != RawBY || bCut)
	{
		if (Abs(UVY) > 1048576.0 || Abs(UBY) > 1048576.0)
			Rebase();
		OVY = UVY;
		OVP = UVP;
		OBY = UBY;
		OBP = UBP;
		DVP = ShortTurn(NVP - RawVP);
		DVY = ShortTurn(NVY - RawVY);
		DBP = ShortTurn(NBP - RawBP);
		DBY = ShortTurn(NBY - RawBY);
		DT = FMax(St - LastRT, 0.0001);
		UVP += DVP;
		UVY += DVY;
		UBP += DBP;
		UBY += DBY;
		RawVP = NVP;
		RawVY = NVY;
		RawBP = NBP;
		RawBY = NBY;
		// A teleport or a respawn has already started a new piece above, and
		// nothing is joined across it. Faster than any hand turns - 5000
		// degrees a second - is a cut too: a respawn or a teleport facing a new
		// way.
		if (!bCut && FMax(Abs(DVY), Abs(DVP)) > FMax(8192.0, 910222.0 * DT))
			RSegN++;
		else if (!bCut && PrevT >= LastRT && St - PrevT > 0.0005)
		{
			if (PrevT > LastRT + 0.0005)
				PushR(PrevT, OVY, OVP, OBY, OBP);
			for (k = 1; k <= N; k++)
			{
				F = float(k) / float(N + 1);
				PushR(PrevT + (St - PrevT) * F, OVY + (UVY - OVY) * F, OVP + (UVP - OVP) * F,
					OBY + (UBY - OBY) * F, OBP + (UBP - OBP) * F);
			}
		}
		PushR(St, UVY, UVP, UBY, UBP);
	}
	else if (St - LastRT > Hold)
		PushR(St, UVY, UVP, UBY, UBP);
}

// Demo seconds per game second, which turns his velocity into units per demo
// second. Read from the driver while it plays at a set speed, and kept from
// the last time it did.
function float GameSpeed()
{
	local float S;

	if (Spec.Driver.PlayBackMode != 2)
	{
		S = Level.TimeDilation / FMax(Spec.Driver.mySpeed, 0.000001);
		if (S > 0.05 && S < 20.0)
		{
			VelScale = S;
			bHaveVelScale = true;
		}
	}
	if (bHaveVelScale)
		return VelScale;
	if (Spec.Driver.PlayBackMode == 2)
		return Level.TimeDilation;
	return 1.0;
}

// The shortest signed turn between two rotator values.
function int ShortTurn(int D)
{
	D = D & 65535;
	if (D >= 32768)
		D -= 65536;
	return D;
}

// A position snapshot at demo time T: position L, eye height E, M, where the
// mover under him (RawMover, if any) was then, and velocity Vel. Carries the
// velocity path on to it, halfway back to the previous snapshot, along the
// average of the two velocities - or starts the path over at L where a piece
// starts.
function PushP(float T, vector L, float E, vector M, vector Vel)
{
	local int Prev, Starts;
	local float H;
	local vector V, R;

	Prev = PHead;
	PHead = (PHead + 1) & 255;
	PT[PHead] = T;
	PX[PHead] = L.X;
	PY[PHead] = L.Y;
	PZ[PHead] = L.Z;
	PE[PHead] = E;
	PSeg[PHead] = Seg;
	PSegZ[PHead] = SegZ;
	PSegC[PHead] = SegC;
	PMov[PHead] = RawMover;
	PMX[PHead] = M.X;
	PMY[PHead] = M.Y;
	PMZ[PHead] = M.Z;
	if (PCount > 0 && PMov[Prev] != None && PMov[Prev] != RawMover)
	{
		V = MoverAt(PMov[Prev], T);
		PLX[PHead] = V.X;
		PLY[PHead] = V.Y;
		PLZ[PHead] = V.Z;
	}
	PES[PHead] = SteadyEye(Prev, T, L.Z);
	if (bRawAir)
		PAir[PHead] = 1;
	else
		PAir[PHead] = 0;

	// ---- the velocity path
	PVX[PHead] = Vel.X;
	PVY[PHead] = Vel.Y;
	PVZ[PHead] = Vel.Z;
	// a piece starts wherever the channel's own piece or the mover under him changes
	Starts = 0;
	if (PCount == 0 || PSeg[Prev] != Seg || PMov[Prev] != RawMover)
		Starts = Starts | 1;
	if (PCount == 0 || PSegZ[Prev] != SegZ || PMov[Prev] != RawMover)
		Starts = Starts | 2;
	if (PCount == 0 || PSegC[Prev] != SegC || PMov[Prev] != RawMover)
		Starts = Starts | 4;
	if ((Starts & 1) != 0)
		VelXYN++;
	if ((Starts & 2) != 0)
		VelZN++;
	if ((Starts & 4) != 0)
		VelCN++;
	VelNew[PHead] = byte(Starts);
	VelXY[PHead] = VelXYN;
	VelZ[PHead] = VelZN;
	VelC[PHead] = VelCN;
	if (PCount == 0)
		PMid[PHead] = T;
	else
		PMid[PHead] = 0.5 * (PT[Prev] + T);
	R = L;
	if (RawMover != None)
		R = L - M;
	if ((Starts & 1) != 0)
	{
		PDX[PHead] = R.X;
		PDY[PHead] = R.Y;
	}
	else
	{
		H = PMid[PHead] - MidOf(Prev, 1);
		PDX[PHead] = PDX[Prev] + 0.5 * (PVX[Prev] + Vel.X) * H;
		PDY[PHead] = PDY[Prev] + 0.5 * (PVY[Prev] + Vel.Y) * H;
	}
	if ((Starts & 2) != 0)
		PDZ[PHead] = R.Z;
	else
		PDZ[PHead] = PDZ[Prev] + 0.5 * (PVZ[Prev] + Vel.Z) * (PMid[PHead] - MidOf(Prev, 2));
	if ((Starts & 4) != 0)
		PDC[PHead] = R.Z;
	else
		PDC[PHead] = PDC[Prev] + 0.5 * (PVZ[Prev] + Vel.Z) * (PMid[PHead] - MidOf(Prev, 4));

	if (PCount < 256)
		PCount++;
	LastPT = T;
}

// The steady eye height of a snapshot at demo time T, height Z. It moves
// towards FTarget by the same share of the gap per second as the game's own
// eye height does, from the previous snapshot's - three times that while
// FTarget is the demo's own eye height. A step within the same camera height
// piece is first given back, as the game does. A teleport or a respawn starts
// it over.
function float SteadyEye(int Prev, float T, float Z)
{
	local float E, K, DZ;

	if (PCount == 0 || PSeg[Prev] != PSeg[PHead])
		return FTarget;
	if (bFTargetDemo)
		K = Exp(-30.0 * FMax(T - PT[Prev], 0.0));
	else
		K = Exp(-10.0 * FMax(T - PT[Prev], 0.0));
	DZ = 0.0;
	if (PSegZ[Prev] != PSegZ[PHead] && PSegC[Prev] == PSegC[PHead])
	{
		DZ = Z - PZ[Prev];
		// a step taken on a mover: the mover's own rise is not his
		if (PMov[PHead] != None && PMov[PHead] == PMov[Prev])
			DZ -= PMZ[PHead] - PMZ[Prev];
	}
	E = (PES[Prev] - DZ) * K + FTarget * (1.0 - K);
	return FMax(E, -0.5 * Rec.CollisionHeight);
}

// The eye height the steady eye height moves towards: standing height; seven
// tenths of it in water; and on the ground while he crouches, the demo's own
// eye height, held between the ground and the height he stands up to.
function float SteadyTarget(bool bAir, float St)
{
	local float B;

	B = Rec.default.BaseEyeHeight;
	bFTargetDemo = false;
	if (Rec.Region.Zone != None && Rec.Region.Zone.bWaterZone)
		return 0.7 * B;
	if (!bAir && RawEye < 0.5 * B
		&& ((Rec.Mesh != None && Rec.GetAnimGroup(Rec.AnimSequence) == 'Ducking')
			|| (RawEye < 0.4 * B && St - ZMovedT > 0.25)))
	{
		bFTargetDemo = true;
		return FClamp(RawEye, 0.0, 0.4 * B);
	}
	return B;
}

// Where mover M was at demo time St - the newest demo frame, which the clock
// passed during this frame: between where it was at the end of last frame and
// where it is now.
function vector MoverAt(Mover M, float St)
{
	local float F;

	if (M != TrackMover || FAdv <= 0.0)
		return M.Location;
	F = FClamp(1.0 - (ClockAcc + Off - St) / FAdv, 0.0, 1.0);
	return TrackLoc + (M.Location - TrackLoc) * F;
}

function PushR(float T, float VY, float VP, float BY, float BP)
{
	RHead = (RHead + 1) & 255;
	RT[RHead] = T;
	RVY[RHead] = VY;
	RVP[RHead] = VP;
	RBY[RHead] = BY;
	RBP[RHead] = BP;
	RSeg[RHead] = RSegN;
	if (RCount < 256)
		RCount++;
	LastRT = T;
}

// Moves every unwrapped yaw by whole turns, so a float keeps its precision.
function Rebase()
{
	local float Shift;
	local int k, i;

	Shift = 65536.0 * int(UVY / 65536.0);
	UVY -= Shift;
	for (k = 0; k < RCount; k++)
	{
		i = (RHead - k) & 255;
		RVY[i] -= Shift;
	}
	Shift = 65536.0 * int(UBY / 65536.0);
	UBY -= Shift;
	for (k = 0; k < RCount; k++)
	{
		i = (RHead - k) & 255;
		RBY[i] -= Shift;
	}
}

// =============================================================================
// The fits: weighted fits through the snapshots near Tau, read at Tau. The
// weight falls smoothly to nothing at Hb before Tau and Hf after it, and the
// result is held to the range of the snapshots it used. With too few
// snapshots in reach a fit declines, and the caller falls back.
// =============================================================================

function float Weight(float D, float Hb, float Hf)
{
	local float U;

	if (D < 0.0)
		U = -D / Hb;
	else
		U = D / Hf;
	if (U >= 1.0)
		return 0.0;
	U = 1.0 - U * U * U;
	return U * U * U;
}

// A weighted straight line through the camera height - Z and eye height
// together - of the snapshots in camera height piece Want, relative to
// snapshot Ref's. The eye height is EyeOf's, written out here for speed.
function bool FitC(float Tau, float Hb, float Hf, int Want, int Ref, out float OC)
{
	local float S0, S1, S2, SC, TC, LoC, HiC, D, W, Den, CV, ERef, E;
	local int k, i, N;

	LoC = 1000000.0;
	HiC = -1000000.0;
	ERef = EyeOf(Ref);
	for (k = 0; k < PCount; k++)
	{
		i = (PHead - k) & 255;
		D = PT[i] - Tau;
		if (D <= -Hb)
			break;
		if (D >= Hf || PSegC[i] != Want)
			continue;
		W = Weight(D, Hb, Hf);
		if (W <= 0.0)
			continue;
		E = PES[i];
		if (bFLand && PSegC[i] == LandSegC && PT[i] < LandEnd)
			E += FMax(LandR - PZ[i] - E, 0.0) * LandW;
		CV = (PZ[i] - PZ[Ref]) + (E - ERef);
		N++;
		S0 += W;
		S1 += W * D;
		S2 += W * D * D;
		SC += W * CV;
		TC += W * D * CV;
		LoC = FMin(LoC, CV);
		HiC = FMax(HiC, CV);
	}
	if (N < 2)
		return false;
	Den = S0 * S2 - S1 * S1;
	if (Den > 0.000001 * S0 * S2)
		OC = (S2 * SC - S1 * TC) / Den;
	else
		OC = SC / S0;
	OC = PZ[Ref] + ERef + FClamp(OC, LoC, HiC);
	return true;
}

// Snapshot i's steady eye height - raised, for a ground snapshot before he
// settles after a landing, to hold the camera where he settles (see
// LandCheck).
function float EyeOf(int i)
{
	if (bFLand && PSegC[i] == LandSegC && PT[i] < LandEnd)
		return PES[i] + FMax(LandR - PZ[i] - PES[i], 0.0) * LandW;
	return PES[i];
}

// A weighted parabola through all four rotation channels of the snapshots in
// rotation piece Want, relative to snapshot Ref's. Needs three snapshots of
// more than a hundredth of the full weight.
function bool FitRot(float Tau, float Hb, float Hf, int Want, int Ref, out float OVY, out float OVP, out float OBY, out float OBP)
{
	local float D, W, U, U2, S0, S1, S2, S3, S4, Det, K0, K1, K2;
	local float TA0, TA1, TA2, TB0, TB1, TB2, TC0, TC1, TC2, TD0, TD1, TD2;
	local float VA, VB, VC, VD, LoA, HiA, LoB, HiB, LoC, HiC, LoD, HiD;
	local int k, i, N;

	LoA = 100000000.0;
	LoB = 100000000.0;
	LoC = 100000000.0;
	LoD = 100000000.0;
	HiA = -100000000.0;
	HiB = -100000000.0;
	HiC = -100000000.0;
	HiD = -100000000.0;
	for (k = 0; k < RCount; k++)
	{
		i = (RHead - k) & 255;
		D = RT[i] - Tau;
		if (D <= -Hb)
			break;
		if (D >= Hf || RSeg[i] != Want)
			continue;
		W = Weight(D, Hb, Hf);
		if (W <= 0.0)
			continue;
		if (W > 0.01)
			N++;
		// time in units of Hb, so the sums stay in range
		U = D / Hb;
		U2 = U * U;
		S0 += W;
		S1 += W * U;
		S2 += W * U2;
		S3 += W * U2 * U;
		S4 += W * U2 * U2;
		VA = RVY[i] - RVY[Ref];
		VB = RVP[i] - RVP[Ref];
		VC = RBY[i] - RBY[Ref];
		VD = RBP[i] - RBP[Ref];
		TA0 += W * VA;
		TA1 += W * U * VA;
		TA2 += W * U2 * VA;
		TB0 += W * VB;
		TB1 += W * U * VB;
		TB2 += W * U2 * VB;
		TC0 += W * VC;
		TC1 += W * U * VC;
		TC2 += W * U2 * VC;
		TD0 += W * VD;
		TD1 += W * U * VD;
		TD2 += W * U2 * VD;
		LoA = FMin(LoA, VA);
		HiA = FMax(HiA, VA);
		LoB = FMin(LoB, VB);
		HiB = FMax(HiB, VB);
		LoC = FMin(LoC, VC);
		HiC = FMax(HiC, VC);
		LoD = FMin(LoD, VD);
		HiD = FMax(HiD, VD);
	}
	if (N < 3)
		return false;
	// the constant term of the solution, by Cramer's rule
	K0 = S2 * S4 - S3 * S3;
	K1 = S1 * S4 - S3 * S2;
	K2 = S1 * S3 - S2 * S2;
	Det = S0 * K0 - S1 * K1 + S2 * K2;
	if (Det <= 0.000001 * S0 * S2 * S4)
		return false;
	OVY = RVY[Ref] + FClamp((TA0 * K0 - S1 * (TA1 * S4 - S3 * TA2) + S2 * (TA1 * S3 - S2 * TA2)) / Det, LoA, HiA);
	OVP = RVP[Ref] + FClamp((TB0 * K0 - S1 * (TB1 * S4 - S3 * TB2) + S2 * (TB1 * S3 - S2 * TB2)) / Det, LoB, HiB);
	OBY = RBY[Ref] + FClamp((TC0 * K0 - S1 * (TC1 * S4 - S3 * TC2) + S2 * (TC1 * S3 - S2 * TC2)) / Det, LoC, HiC);
	OBP = RBP[Ref] + FClamp((TD0 * K0 - S1 * (TD1 * S4 - S3 * TD2) + S2 * (TD1 * S3 - S2 * TD2)) / Det, LoD, HiD);
	return true;
}

// =============================================================================
// The velocity path
// =============================================================================

// Snapshot i's path time on channel Chan: PMid, or the snapshot's own time
// where the channel's piece starts.
function float MidOf(int i, int Chan)
{
	if ((VelNew[i] & Chan) != 0)
		return PT[i];
	return PMid[i];
}

// Snapshot i's piece on channel Chan.
function int VelocityPiece(int i, int Chan)
{
	if (Chan == 1)
		return VelXY[i];
	if (Chan == 2)
		return VelZ[i];
	return VelC[i];
}

// The snapshot after i, or -1 when i is the newest.
function int NextP(int i)
{
	if (i == PHead)
		return -1;
	return (i + 1) & 255;
}

// The velocity path of channel Chan at time T, from snapshot J on: on from
// the path time of J or of the snapshot after it, whichever T has passed,
// with the velocity turning evenly towards the next snapshot's. OA is X or
// the height, OB is Y.
function PathAt(int Chan, int J, float T, out float OA, out float OB)
{
	local int B, N, Q;
	local float X, H;

	B = J;
	X = T - MidOf(J, Chan);
	Q = -1;
	N = NextP(J);
	if (N >= 0 && VelocityPiece(N, Chan) == VelocityPiece(J, Chan))
	{
		if (T <= PMid[N])
			Q = N;
		else
		{
			B = N;
			X = T - PMid[N];
			Q = NextP(N);
			if (Q >= 0 && VelocityPiece(Q, Chan) != VelocityPiece(N, Chan))
				Q = -1;
		}
	}
	if (Q >= 0)
		H = 2.0 * FMax(PMid[Q] - MidOf(B, Chan), 0.000001);
	if (Chan == 1)
	{
		OA = PDX[B] + PVX[B] * X;
		OB = PDY[B] + PVY[B] * X;
		if (Q >= 0)
		{
			OA += (PVX[Q] - PVX[B]) / H * X * X;
			OB += (PVY[Q] - PVY[B]) / H * X * X;
		}
		return;
	}
	if (Chan == 2)
		OA = PDZ[B] + PVZ[B] * X;
	else
		OA = PDC[B] + PVZ[B] * X;
	if (Q >= 0)
		OA += (PVZ[Q] - PVZ[B]) / H * X * X;
	OB = 0.0;
}

// How far snapshot i sits from the velocity path of channel Chan, off the
// mover under him.
function Resid(int Chan, int i, out float RA, out float RB)
{
	local float X;
	local vector R;

	R.X = PX[i];
	R.Y = PY[i];
	R.Z = PZ[i];
	if (PMov[i] != None)
	{
		R.X -= PMX[i];
		R.Y -= PMY[i];
		R.Z -= PMZ[i];
	}
	X = PT[i] - MidOf(i, Chan);
	RB = 0.0;
	if (Chan == 1)
	{
		RA = R.X - (PDX[i] + PVX[i] * X);
		RB = R.Y - (PDY[i] + PVY[i] * X);
	}
	else if (Chan == 2)
		RA = R.Z - (PDZ[i] + PVZ[i] * X);
	else
		RA = R.Z + EyeOf(i) - (PDC[i] + PVZ[i] * X);
}

// How far the snapshots of J's piece sit from the velocity path of channel
// Chan at time T: a weighted mean over the tenth of a second before T for
// X/Y, a weighted straight line over the twentieth before T for the heights,
// reaching Hf ahead. Without a snapshot in reach, J's own.
function Drift(int Chan, int J, float T, float Hf, out float OA, out float OB)
{
	local float Hb, D, W, S0, S1, S2, SA, TA, SB, LoA, HiA, LoB, HiB, RA, RB, Den;
	local int k, i, N, Want;

	Want = VelocityPiece(J, Chan);
	Hb = 0.05;
	if (Chan == 1)
		Hb = 0.1;
	LoA = 1000000.0;
	LoB = 1000000.0;
	HiA = -1000000.0;
	HiB = -1000000.0;
	for (k = 0; k < PCount; k++)
	{
		i = (PHead - k) & 255;
		D = PT[i] - T;
		if (D <= -Hb)
			break;
		if (D >= Hf || VelocityPiece(i, Chan) != Want)
			continue;
		W = Weight(D, Hb, Hf);
		if (W <= 0.0)
			continue;
		Resid(Chan, i, RA, RB);
		N++;
		S0 += W;
		S1 += W * D;
		S2 += W * D * D;
		SA += W * RA;
		TA += W * D * RA;
		SB += W * RB;
		LoA = FMin(LoA, RA);
		HiA = FMax(HiA, RA);
		LoB = FMin(LoB, RB);
		HiB = FMax(HiB, RB);
	}
	if (N == 0)
	{
		Resid(Chan, J, OA, OB);
		return;
	}
	OA = SA / S0;
	OB = SB / S0;
	if (Chan != 1 && N >= 2)
	{
		Den = S0 * S2 - S1 * S1;
		if (Den > 0.000001 * S0 * S2)
			OA = (S2 * SA - S1 * TA) / Den;
	}
	OA = FClamp(OA, LoA, HiA);
	OB = FClamp(OB, LoB, HiB);
}

// Where the mover under him at snapshot J was at time T, on a straight line
// to the next snapshot of the same piece. None: nothing.
function vector MoverPosAt(int Chan, int J, float T)
{
	local vector M;
	local int N;
	local float A;

	if (PMov[J] == None)
		return vect(0,0,0);
	M.X = PMX[J];
	M.Y = PMY[J];
	M.Z = PMZ[J];
	N = NextP(J);
	if (N < 0 || VelocityPiece(N, Chan) != VelocityPiece(J, Chan))
		return M;
	A = FClamp((T - PT[J]) / FMax(PT[N] - PT[J], 0.000001), 0.0, 1.0);
	M.X += (PMX[N] - PMX[J]) * A;
	M.Y += (PMY[N] - PMY[J]) * A;
	M.Z += (PMZ[N] - PMZ[J]) * A;
	return M;
}

// Channel Chan at time T within J's piece: the velocity path, its drift from
// the snapshots and the mover under him.
function PieceVal(int Chan, int J, float T, float Hf, out float OA, out float OB)
{
	local float PA, PB, DA, DB;
	local vector M;

	PathAt(Chan, J, T, PA, PB);
	Drift(Chan, J, T, Hf, DA, DB);
	M = MoverPosAt(Chan, J, T);
	if (Chan == 1)
	{
		OA = PA + DA + M.X;
		OB = PB + DB + M.Y;
		return;
	}
	OA = PA + DA + M.Z;
	OB = 0.0;
}

// Channel Chan at Tau, A of the way from J to Nx. A new piece between them -
// a step, a take-off or a landing - joins each side's own value at its end of
// the gap in a straight line.
function ChanAt(int Chan, float Tau, int J, int Nx, float A, float Hf, out float OA, out float OB)
{
	local float AA, AB, BA, BB;

	if (Nx >= 0 && VelocityPiece(Nx, Chan) != VelocityPiece(J, Chan) && PSeg[Nx] == PSeg[J])
	{
		PieceVal(Chan, J, PT[J], Hf, AA, AB);
		PieceVal(Chan, Nx, PT[Nx], Hf, BA, BB);
		OA = AA + (BA - AA) * A;
		OB = AB + (BB - AB) * A;
		return;
	}
	PieceVal(Chan, J, Tau, Hf, OA, OB);
}

// =============================================================================
// Showing it
// =============================================================================

// The position and eye height at Tau. J is the newest snapshot at or before
// Tau and Nx the one after it; before the first snapshot the first one is
// held. The camera height is taken on its own channel, and the eye height is
// what it leaves above the body. Hb and Hf are the reach of LandCheck.
function bool EvalLoc(float Tau, float Hb, float Hf, out vector L, out float OE)
{
	local int k, i, J, Nx;
	local float A, HfL, FX, FY, FZ, CF, Spare;

	J = -1;
	Nx = -1;
	for (k = 0; k < PCount; k++)
	{
		i = (PHead - k) & 255;
		if (PT[i] <= Tau)
		{
			J = i;
			break;
		}
		Nx = i;
	}
	bFLand = false;
	if (J >= 0)
		LandCheck(Tau, J, Nx, Hb, Hf);
	if (J < 0)
	{
		if (Nx < 0)
			return false;
		J = Nx;
		Nx = -1;
	}
	// how far Tau is from J to Nx, for the straight joins
	A = 0.0;
	if (Nx >= 0)
		A = FClamp((Tau - PT[J]) / FMax(PT[Nx] - PT[J], 0.000001), 0.0, 1.0);
	// the drift reaches ahead to every snapshot read so far
	HfL = FMax(FNow - Tau, 0.25 * FMax(Interval(), 0.001));
	ChanAt(1, Tau, J, Nx, A, HfL, FX, FY);
	ChanAt(2, Tau, J, Nx, A, HfL, FZ, Spare);
	L.X = FX;
	L.Y = FY;
	L.Z = FZ;
	L += MoverCarry(Tau, J);
	ChanAt(4, Tau, J, Nx, A, HfL, CF, Spare);
	OE = CF - FZ;
	// coming down into a landing: never below where he settles
	if (bFLand && Tau < PT[LandG])
		OE += FMax(LandR - (FZ + OE), 0.0) * LandW;
	return true;
}

// Holds the camera where he settles after a landing, instead of where the
// landing sank him into the floor. LandR is the highest camera height among
// the ground piece's snapshots within two and a half demo frames of the
// landing. Only for a real landing: he comes down into it, and the sink is no
// deeper than a landing's. The hold comes in over a hundredth of a second.
//
// Tau is the moment shown, J the newest snapshot at or before it, Nx the one
// after (-1: none), Hb and Hf the reach. Sets bFLand and, with it, LandG (the
// landing's first ground snapshot), LandSegC, LandEnd, LandR and LandW (how
// far the hold has come in).
function LandCheck(float Tau, int J, int Nx, float Hb, float Hf)
{
	local int a, g, b, i, s, n, ka, kg, p;
	local float Ts, V, CV, MinC, LowToS, T1, C1;

	if (PCount < 3)
		return;
	Ts = 2.5 * FMax(Interval(), 0.001);
	if (PAir[J] != 0)
	{
		// in the air: the next camera height piece
		if (Nx < 0)
			return;
		a = J;
		g = Nx;
		while (PSegC[g] == PSegC[J])
		{
			if (g == PHead)
				return;
			a = g;
			g = (g + 1) & 255;
		}
	}
	else
	{
		// on the ground: the first snapshot of this piece, while its raised
		// snapshots can still be in a fit's reach
		g = J;
		kg = (PHead - g) & 255;
		while (kg + 1 < PCount && PSegC[(g - 1) & 255] == PSegC[J] && PT[g] >= Tau - Ts - Hb)
		{
			g = (g - 1) & 255;
			kg++;
		}
		if (kg + 1 >= PCount || PT[g] < Tau - Ts - Hb)
			return;
		a = (g - 1) & 255;
		if (PAir[a] == 0)
			return;
	}
	// from the air onto the ground, no teleport in between
	if (PAir[g] != 0 || PSeg[g] != PSeg[a])
		return;
	// coming down into it: the latest earlier snapshot of the air piece at
	// another height is higher
	ka = (PHead - a) & 255;
	b = a;
	for (n = 0; n < 16 && ka + n + 1 < PCount; n++)
	{
		p = (b - 1) & 255;
		if (PSegC[p] != PSegC[a])
			break;
		b = p;
		if (PZ[b] != PZ[a])
			break;
	}
	if (PZ[b] <= PZ[a])
		return;
	V = (PZ[b] - PZ[a]) / FMax(PT[a] - PT[b], 0.0001);
	// where he settles, and how deep he sank before it
	LandR = PZ[g] + PES[g];
	s = g;
	MinC = FMin(LandR, PZ[a] + PES[a]);
	LowToS = MinC;
	i = g;
	while (i != PHead)
	{
		i = (i + 1) & 255;
		if (PSegC[i] != PSegC[g] || PT[i] > PT[g] + Ts)
			break;
		CV = PZ[i] + PES[i];
		MinC = FMin(MinC, CV);
		if (CV > LandR)
		{
			LandR = CV;
			s = i;
			LowToS = MinC;
		}
	}
	if (LandR - LowToS < 0.05 || LandR - LowToS > 0.012 * V + 4.0)
		return;
	// where the approach is when the hold is in (bFLand is still false, so
	// FitC reads the plain snapshots)
	T1 = PT[g] + Ts - DCur + 0.01;
	if (T1 < PT[g])
	{
		if (!FitC(T1, Hb, Hf, PSegC[a], a, C1))
			C1 = PZ[a] + PES[a];
		if (C1 < LandR - 1.0)
			return;
	}
	LandG = g;
	LandSegC = PSegC[g];
	LandEnd = PT[s];
	LandW = FClamp((Tau - (PT[g] + Ts - DCur)) / 0.01, 0.0, 1.0);
	bFLand = true;
}

// How far a mover under the player has moved between Tau and now - or, if he
// has left it, the moment he left. Added to his position at Tau, it keeps him
// standing on the mover as it is drawn now. J is the newest snapshot at or
// before Tau (the oldest, if Tau is before them all).
function vector MoverCarry(float Tau, int J)
{
	local int F, Last, k;
	local float A;
	local Mover M;
	local vector E, T;

	// the mover J stands on, or else the next one he lands on
	F = J;
	while (PMov[F] == None)
	{
		if (F == PHead)
			return vect(0,0,0);
		F = (F + 1) & 255;
	}
	M = PMov[F];
	if (M.bDeleteMe)
		return vect(0,0,0);
	Last = F;
	while (Last != PHead && PMov[(Last + 1) & 255] == M)
		Last = (Last + 1) & 255;
	// where the mover is taken to: where it is now while he is on it, where
	// it was when he left once he has
	if (Last == PHead)
		E = M.Location;
	else
	{
		k = (Last + 1) & 255;
		E.X = PLX[k];
		E.Y = PLY[k];
		E.Z = PLZ[k];
	}
	// where it was at Tau: before he landed, where it was when he did
	T.X = PMX[F];
	T.Y = PMY[F];
	T.Z = PMZ[F];
	if (F == J)
	{
		k = (J + 1) & 255;
		if (J != Last)
		{
			A = FClamp((Tau - PT[J]) / FMax(PT[k] - PT[J], 0.000001), 0.0, 1.0);
			T.X += (PMX[k] - PMX[J]) * A;
			T.Y += (PMY[k] - PMY[J]) * A;
			T.Z += (PMZ[k] - PMZ[J]) * A;
		}
		else if (Last != PHead)
		{
			// between his last snapshot on it and his first off it
			A = FClamp((Tau - PT[J]) / FMax(PT[k] - PT[J], 0.000001), 0.0, 1.0);
			T.X += (PLX[k] - PMX[J]) * A;
			T.Y += (PLY[k] - PMY[J]) * A;
			T.Z += (PLZ[k] - PMZ[J]) * A;
		}
	}
	return E - T;
}

// The four rotation channels at Tau: the parabola through the snapshots from
// two demo frames - at least a thirtieth of a second - before Tau to every
// snapshot read so far, or else the snapshots either side joined in a
// straight line.
function bool EvalRot(float Tau, out float OVY, out float OVP, out float OBY, out float OBP)
{
	local int k, i, J, Nx;
	local float IE;

	J = -1;
	Nx = -1;
	for (k = 0; k < RCount; k++)
	{
		i = (RHead - k) & 255;
		if (RT[i] <= Tau)
		{
			J = i;
			break;
		}
		Nx = i;
	}
	if (J < 0)
	{
		if (Nx < 0)
			return false;
		J = Nx;
		Nx = -1;
	}
	IE = FMax(Interval(), 0.001);
	if (FitRot(Tau, FMax(0.033, 2.0 * IE), FMax(FNow - Tau, 0.25 * IE), RSeg[J], J, OVY, OVP, OBY, OBP))
		return true;
	return HeldRot(Tau, OVY, OVP, OBY, OBP);
}

// The four rotation channels at Tau, the snapshots either side joined in a
// straight line.
function bool HeldRot(float Tau, out float OVY, out float OVP, out float OBY, out float OBP)
{
	local int k, i, J, Nx;
	local float A;

	J = -1;
	Nx = -1;
	for (k = 0; k < RCount; k++)
	{
		i = (RHead - k) & 255;
		if (RT[i] <= Tau)
		{
			J = i;
			break;
		}
		Nx = i;
	}
	if (J < 0)
	{
		if (Nx < 0)
			return false;
		J = Nx;
		Nx = -1;
	}
	OVY = RVY[J];
	OVP = RVP[J];
	OBY = RBY[J];
	OBP = RBP[J];
	if (Nx >= 0 && RSeg[Nx] == RSeg[J])
	{
		A = FClamp((Tau - RT[J]) / FMax(RT[Nx] - RT[J], 0.000001), 0.0, 1.0);
		OVY += (RVY[Nx] - RVY[J]) * A;
		OVP += (RVP[Nx] - RVP[J]) * A;
		OBY += (RBY[Nx] - RBY[J]) * A;
		OBP += (RBP[Nx] - RBP[J]) * A;
	}
	return true;
}

// Shows no moment before the latest teleport or respawn, so the player holds
// where he landed until the delay has passed. A clock short of the cut is
// moved on to it.
function SnapToCut()
{
	local float Now;

	if (!bHaveCut)
		return;
	bHaveCut = false;
	Now = ClockAcc + Off;
	if (Now < CutT)
		Off += CutT - Now;
	ShowFrom = CutT;
}

// Places the player at the moments shown: position, eye height and rotation,
// with the view held level.
function Place()
{
	local float IE, Hf, Hb, NewestP, NewestR, TP, TR, OE, Now;
	local float OVY, OVP, OBY, OBP;
	local vector L;
	local rotator R;
	local bool bHaveL, bHaveR, bOldCW;

	bFLand = false;
	if (PCount == 0 || RCount == 0)
		return;
	SnapToCut();
	// the delay in use eases towards the delay by a tenth of the demo advance
	if (!bDCur)
	{
		DCur = Delay();
		bDCur = true;
	}
	DCur += FClamp(Delay() - DCur, -0.1 * FAdv, 0.1 * FAdv);
	Now = ClockAcc + Off;
	FNow = Now;
	IE = FMax(Interval(), 0.001);

	// ---- the moments shown
	TP = FMax(Now - DCur, ShowFrom);
	NewestP = PT[PHead];
	if (TP > NewestP)
	{
		// Past the newest snapshot: hold there. Held on for a quarter second
		// while demo frames keep coming, the clock itself is off: take it back
		// to what the demo says.
		TP = NewestP;
		StarveAdv += FStep;
		if (StarveAdv > 0.25)
		{
			Off = LastSpt - T0 - ClockAcc;
			OffEst = Off;
			CurMax = Off;
			PrevMax = Off;
			BucketAcc = 0.0;
			StarveAdv = 0.0;
		}
	}
	else
		StarveAdv = 0.0;
	if (bHaveTau && TP < TauP)
		TP = TauP;
	// from the clock, not from TP: TP holds while he stands still, and a turn
	// on the spot must not hold with it
	TR = FMax(Now - DCur, ShowFrom);
	NewestR = RT[RHead];
	if (TR > NewestR)
		TR = NewestR;
	if (bHaveTau && TR < TauR)
		TR = TauR;
	TauP = TP;
	TauR = TR;
	bHaveTau = true;

	Hf = FMax(DCur - IE, 2.0 * IE);
	Hb = FMax(2.0 * Hf, 0.04);
	bHaveL = EvalLoc(TP, Hb, Hf, L, OE);
	bHaveR = EvalRot(TR, OVY, OVP, OBY, OBP);

	// ---- movement
	if (bHaveL)
	{
		// Placed with world collision off.
		bOldCW = Rec.bCollideWorld;
		Rec.bCollideWorld = false;
		Rec.SetLocation(L);
		Rec.bCollideWorld = bOldCW;
		WLoc = Rec.Location;
		bWroteLoc = true;
		Rec.EyeHeight = OE;
		// Set the way udemo sets them at the start of every frame.
		Rec.BaseEyeHeight = OE;
		Rec.ShakeVert = 0.0;
		WEye = Rec.EyeHeight;
		bWroteEye = true;
	}

	// ---- rotation, with the roll held level
	if (bHaveR)
	{
		R = Rec.ViewRotation;
		R.Yaw = int(OVY) & 65535;
		R.Pitch = int(OVP) & 65535;
		R.Roll = 0;
		Rec.ViewRotation = R;
		R = Rec.Rotation;
		R.Yaw = int(OBY) & 65535;
		R.Pitch = int(OBP) & 65535;
		Rec.SetRotation(R);
		WVP = Rec.ViewRotation.Pitch;
		WVY = Rec.ViewRotation.Yaw;
		WBP = Rec.Rotation.Pitch;
		WBY = Rec.Rotation.Yaw;
		bWroteRot = true;
	}
	else if (Rec.ViewRotation.Roll != 0)
	{
		// holds the roll level on frames with no rotation to write
		R = Rec.ViewRotation;
		R.Roll = 0;
		Rec.ViewRotation = R;
	}

	if (bHaveL || bHaveR)
		FollowTrailers();
}

// Leaves IG+'s own eye height model nothing to give back, so the player's
// update keeps the eye height as written.
function CalmOwnEye()
{
	Rec.ShakeVert = 0.0;
	Rec.SetPropertyText("EyeHeightOffset", "0");
	Rec.SetPropertyText("OldZ", string(Rec.Location.Z));
	Rec.SetPropertyText("OldBaseEyeHeight", string(Rec.BaseEyeHeight));
	Rec.SetPropertyText("OldShakeVert", "0");
}

// Puts the effects that trail the player - the shield belt, powerup glows -
// where their physics puts them on the pose just written.
function FollowTrailers()
{
	local Actor A;

	if (Rec == None)
		return;
	foreach Rec.ChildActors(class'Actor', A)
	{
		if (A.Physics != PHYS_Trailer)
			continue;
		if (A.bTrailerPrePivot)
			A.SetLocation(Rec.Location + A.PrePivot);
		else
			A.SetLocation(Rec.Location);
		if (A.bTrailerSameRotation)
			A.SetRotation(Rec.Rotation);
	}
}

event Destroyed()
{
	Release();
	if (Late != None && !Late.bDeleteMe)
		Late.Destroy();
	Super.Destroyed();
}

defaultproperties
{
	bHidden=True
	RemoteRole=ROLE_None
	DrawType=DT_None
	PHead=-1
	RHead=-1
	RateFix=1.000000
}
