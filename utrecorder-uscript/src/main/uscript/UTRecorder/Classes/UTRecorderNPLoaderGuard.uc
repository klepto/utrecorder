//=============================================================================
// UTRecorderNPLoaderGuard - destroys every NPLoader replication info the demo
// spawns, so that the client code the demo replays on it never runs.
//
// Matched by the class name NPLReplicationInfo, which every NPLoader version
// uses.
//
// The guard destroys an NPLoader replication info only while the demo spawns
// it. The engine then reports "Actor not spawned for new channel" and leaves
// the actor channel of the demo without an actor, so the demo stream sends
// nothing more to it. A replication info the demo spawned before the guard
// existed is already the actor of its channel. The guard leaves it alive,
// since the channel keeps a pointer to its actor: once the level frees a
// destroyed actor, the next bunch the demo sends to the channel reads freed
// memory and crashes the game.
//=============================================================================
class UTRecorderNPLoaderGuard extends SpawnNotify;

// Returns whether A is an NPLoader replication info that is not destroyed.
static function bool IsNPLoader(Actor A)
{
	return A != None && !A.bDeleteMe && A.IsA('NPLReplicationInfo');
}

// Logs A when it is an NPLoader replication info that the demo spawned before
// the guard, which the guard leaves alive.
static function ReportActor(Actor A)
{
	if (IsNPLoader(A))
		Log("UTRecorder: left NPLoader client code of" @ A @ "bound to its demo channel", 'UTRecorder');
}

// Destroys A as the demo spawns it when it is an NPLoader replication info,
// after giving it authority, since a client destroys only actors it has
// authority over.
simulated event Actor SpawnNotification(Actor A)
{
	if (!IsNPLoader(A))
		return A;

	A.Role = ROLE_Authority;
	if (A.Destroy())
		Log("UTRecorder: destroyed NPLoader client code of" @ A, 'UTRecorder');
	else
		Log("UTRecorder: could not destroy NPLoader client code of" @ A, 'UTRecorder');
	return A;
}

defaultproperties
{
	ActorClass=Class'Engine.ReplicationInfo'
	RemoteRole=ROLE_None
}
