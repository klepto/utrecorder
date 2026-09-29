//=============================================================================
// UTRecorderSmoothLate - does the work of a UTRecorderSmooth for the frame,
// after the followed player has had its own update.
//
// The followed player owns this actor, and an actor whose owner has not
// updated yet waits until it has. One that is no longer its smoother's
// destroys itself.
//=============================================================================
class UTRecorderSmoothLate extends Actor;

var UTRecorderSmooth Smooth;

// Runs the smoother's frame, or destroys this actor once the smoother no
// longer uses it.
event Tick(float Delta)
{
	if (Smooth == None || Smooth.bDeleteMe || Smooth.Late != self)
	{
		Destroy();
		return;
	}
	Smooth.LateIdle = 0;
	Smooth.RunFrame(Delta);
}

defaultproperties
{
	bHidden=True
	RemoteRole=ROLE_None
	DrawType=DT_None
}
