//=============================================================================
// UTRecorderHUD - InterceptHUD that drops the linked player's string messages.
//
// UTRecorderConsole puts it on the linked player in place of udemo's own
// InterceptHUD when HideMessages is on. Chat and ClientMessage lines never
// reach the viewer's HUD; critical events and localized messages still pass
// through, so centre announcements keep drawing.
//=============================================================================
class UTRecorderHUD extends InterceptHUD;

simulated function Message( PlayerReplicationInfo PRI, coerce string Msg, name MsgType )
{
	if (MsgType == 'CriticalEvent')
		Super.Message(PRI, Msg, MsgType);
}
