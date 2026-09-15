class UTRecorderHUD extends ChallengeHUD;

var bool bHideWeapon;

simulated function PostRender( canvas Canvas )
{
	HUDSetup(Canvas);

	if (PawnOwner == None || PlayerOwner == None)
		return;

	if (PlayerOwner.bBehindView || PawnOwner.Weapon == None || Level.LevelAction != LEVACT_None)
		return;

	Canvas.DrawColor = WhiteColor;
	if (!bHideWeapon)
		PawnOwner.Weapon.PostRender(Canvas);

	if (!PawnOwner.Weapon.bOwnsCrosshair)
		DrawCrossHair(Canvas, 0, 0);
}

defaultproperties
{
	bHideHUD=True
	bHideStatus=True
	bHideAmmo=True
	bHideTeamInfo=True
	bHideFrags=True
	bHideFaces=True
	bHideNoviceMessages=True
	bHideWeapon=False
}
