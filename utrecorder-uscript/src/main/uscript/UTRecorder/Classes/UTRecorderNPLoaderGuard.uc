//=============================================================================
// UTRecorderNPLoaderGuard - destroys every NPLoader replication info the demo
// spawns, so that the client code the demo replays on it never runs.
//
// Matched by the class name NPLReplicationInfo, which every NPLoader version
// uses.
//=============================================================================
class UTRecorderNPLoaderGuard extends SpawnNotify;

// Destroys A when it is an NPLoader replication info, after giving it
// authority, since a client destroys only actors it has authority over.
static function DisableActor(Actor A)
{
	if (A == None || A.bDeleteMe || !A.IsA('NPLReplicationInfo'))
		return;

	A.Role = ROLE_Authority;
	if (A.Destroy())
		Log("UTRecorder: destroyed NPLoader client code of" @ A, 'UTRecorder');
	else
		Log("UTRecorder: could not destroy NPLoader client code of" @ A, 'UTRecorder');
}

// Destroys A as the demo spawns it when it is an NPLoader replication info.
simulated event Actor SpawnNotification(Actor A)
{
	DisableActor(A);
	return A;
}

defaultproperties
{
	ActorClass=Class'Engine.ReplicationInfo'
	RemoteRole=ROLE_None
}
