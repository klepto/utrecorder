//=============================================================================
// UTRecorderHUD - InterceptHUD that drops the string messages sent to its
// owner.
//
// Of the lines that reach Message, only critical events are forwarded.
// Localized messages do not go through Message, so they still draw.
//=============================================================================
class UTRecorderHUD extends InterceptHUD;

// Forwards the message when MsgType is CriticalEvent, and drops it otherwise.
simulated function Message( PlayerReplicationInfo PRI, coerce string Msg, name MsgType )
{
	if (MsgType == 'CriticalEvent')
		Super.Message(PRI, Msg, MsgType);
}
