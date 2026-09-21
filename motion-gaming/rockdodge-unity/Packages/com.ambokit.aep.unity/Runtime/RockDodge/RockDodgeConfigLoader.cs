using System;
using UnityEngine;

namespace AmboKit.AEP.RockDodge
{
    [Serializable] internal sealed class CanonicalRockDodgeConfig
    {
        public int schemaVersion; public string experienceId,mode,scoreRule; public int startingLives; public long difficultyIntervalMs,minimumSpawnDelayFloorMs,minimumSpawnEveryMs,maximumSpawnEveryMs,jumpDurationMs,crouchDurationMs;
        public float speedMultiplierPerLevel,spawnIntervalMultiplierPerLevel,minimumRockSpeed,maximumRockSpeed,playerMinX,playerMaxX,playerY,playerHalfWidth,playerHalfHeight,rockRadiusMin,rockRadiusMax,spawnXMin,spawnXMax,offscreenY,jumpPlayerYOffset,crouchHeightMultiplier,crouchWidthMultiplier;
    }
    internal static class RockDodgeConfigLoader
    {
        internal static RockDodgeConfig LoadCanonical()
        {
            var asset=Resources.Load<TextAsset>("AEP/rockdodge.v2");
            if(asset==null)throw new InvalidOperationException("Canonical Rock Dodge config missing: Resources/AEP/rockdodge.v2.json");
            var j=JsonUtility.FromJson<CanonicalRockDodgeConfig>(asset.text);
            if(j==null||j.schemaVersion!=2||j.experienceId!="rock-dodge"||j.mode!="survival")throw new InvalidOperationException("Unsupported or invalid canonical Rock Dodge config v2.");
            return new RockDodgeConfig{StartingLives=j.startingLives,DifficultyIntervalMs=j.difficultyIntervalMs,SpeedMultiplierPerLevel=j.speedMultiplierPerLevel,SpawnIntervalMultiplierPerLevel=j.spawnIntervalMultiplierPerLevel,MinimumSpawnDelayFloorMs=j.minimumSpawnDelayFloorMs,MinimumSpawnEveryMs=j.minimumSpawnEveryMs,MaximumSpawnEveryMs=j.maximumSpawnEveryMs,MinimumRockSpeed=j.minimumRockSpeed,MaximumRockSpeed=j.maximumRockSpeed,PlayerMinX=j.playerMinX,PlayerMaxX=j.playerMaxX,PlayerY=j.playerY,PlayerHalfWidth=j.playerHalfWidth,PlayerHalfHeight=j.playerHalfHeight,RockRadiusMin=j.rockRadiusMin,RockRadiusMax=j.rockRadiusMax,JumpDurationMs=j.jumpDurationMs,CrouchDurationMs=j.crouchDurationMs,SpawnXMin=j.spawnXMin,SpawnXMax=j.spawnXMax,OffscreenY=j.offscreenY,JumpPlayerYOffset=j.jumpPlayerYOffset,CrouchHeightMultiplier=j.crouchHeightMultiplier,CrouchWidthMultiplier=j.crouchWidthMultiplier};
        }
    }
}
