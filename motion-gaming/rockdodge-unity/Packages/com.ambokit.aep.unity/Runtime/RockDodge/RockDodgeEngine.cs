using System;
using System.Collections.Generic;
using UnityEngine;
using AmboKit.AEP.Core;

namespace AmboKit.AEP.RockDodge
{
    [Serializable] public sealed class RockDodgeConfig
    {
        public int StartingLives=3; public long DifficultyIntervalMs=10000; public float SpeedMultiplierPerLevel=1.18f,SpawnIntervalMultiplierPerLevel=.85f; public long MinimumSpawnDelayFloorMs=280;
        public long MinimumSpawnEveryMs=720, MaximumSpawnEveryMs=1350; public float MinimumRockSpeed=.22f,MaximumRockSpeed=.34f,PlayerMinX=.10f,PlayerMaxX=.90f,PlayerY=.82f,PlayerHalfWidth=.052f,PlayerHalfHeight=.105f,RockRadiusMin=.034f,RockRadiusMax=.058f;
        public long JumpDurationMs=620,CrouchDurationMs=620; public float SpawnXMin=.11f,SpawnXMax=.89f,OffscreenY=1.04f,JumpPlayerYOffset=-.11f,CrouchHeightMultiplier=.48f,CrouchWidthMultiplier=1.12f;
    }
    [Serializable] public struct RockModel { public long Id; public float X,Y,Radius,RotationDegrees; public int VisualSeed; }
    public sealed class RockDodgeSnapshot
    {
        public bool Running,Completed,Won; public float PlayerX; public bool PlayerJumping,PlayerCrouching; public int Lives,Score,DifficultyLevel; public long ElapsedMs; public readonly List<RockModel> Rocks=new List<RockModel>();
    }
    public sealed class RockDodgeEngine : IExperienceEngine<RockDodgeSnapshot>
    {
        struct R { public long id; public float x,y,speed,radius,rot,spin; public int seed; }
        readonly RockDodgeConfig c; readonly System.Random rng; readonly List<R> rocks=new List<R>(); long nextId=1,elapsed,nextSpawn,jumpUntil,crouchUntil; bool running,completed; int lives; float playerX=.5f;
        public RockDodgeEngine(RockDodgeConfig config=null,int? seed=null){c=config??new RockDodgeConfig();rng=seed.HasValue?new System.Random(seed.Value):new System.Random();}
        public void Start(){rocks.Clear();nextId=1;elapsed=0;nextSpawn=SpawnDelay(LevelFor(0));jumpUntil=crouchUntil=0;lives=c.StartingLives;playerX=.5f;running=true;completed=false;}
        public void OnPlayerMotion(PlayerMotionState m){if(!running)return;float n=(m.Horizontal+1)*.5f;playerX=Mathf.Lerp(c.PlayerMinX,c.PlayerMaxX,Mathf.Clamp01(n));if(m.Jumping)jumpUntil=Math.Max(jumpUntil,elapsed+120);if(m.Crouching)crouchUntil=Math.Max(crouchUntil,elapsed+120);}
        public void OnPlayerAction(PlayerAction a){if(!running)return;if(a==PlayerAction.Jump)jumpUntil=elapsed+c.JumpDurationMs;if(a==PlayerAction.Crouch)crouchUntil=elapsed+c.CrouchDurationMs;}
        public RockDodgeSnapshot Tick(long dt){if(!running||dt<=0)return Snapshot();elapsed+=dt;int level=LevelFor(elapsed);nextSpawn-=dt;while(nextSpawn<=0){Spawn(level);nextSpawn+=SpawnDelay(level);}float sec=dt/1000f;
            for(int i=0;i<rocks.Count;i++){var r=rocks[i];r.y+=r.speed*sec;r.rot=(r.rot+r.spin*sec)%360;rocks[i]=r;}
            bool jumping=elapsed<jumpUntil,crouching=elapsed<crouchUntil;float py=jumping?c.PlayerY+c.JumpPlayerYOffset:c.PlayerY,ph=crouching?c.PlayerHalfHeight*c.CrouchHeightMultiplier:c.PlayerHalfHeight,pw=crouching?c.PlayerHalfWidth*c.CrouchWidthMultiplier:c.PlayerHalfWidth;
            for(int i=rocks.Count-1;i>=0;i--){var r=rocks[i];bool hitX=Mathf.Abs(r.x-playerX)<=r.radius+pw,hitY=Mathf.Abs(r.y-py)<=r.radius+ph;if(hitX&&hitY){lives--;rocks.RemoveAt(i);if(lives<=0){Finish();break;}}else if(r.y-r.radius>c.OffscreenY)rocks.RemoveAt(i);}return Snapshot();}
        public RockDodgeSnapshot Snapshot(){var s=new RockDodgeSnapshot{Running=running,Completed=completed,Won=false,PlayerX=playerX,PlayerJumping=elapsed<jumpUntil,PlayerCrouching=elapsed<crouchUntil,Lives=lives,Score=(int)(elapsed/1000),ElapsedMs=elapsed,DifficultyLevel=LevelFor(elapsed)};foreach(var r in rocks)s.Rocks.Add(new RockModel{Id=r.id,X=r.x,Y=r.y,Radius=r.radius,RotationDegrees=r.rot,VisualSeed=r.seed});return s;}
        int LevelFor(long ms)=>1+(int)(ms/c.DifficultyIntervalMs);
        void Spawn(int level){float x=c.SpawnXMin+(float)rng.NextDouble()*(c.SpawnXMax-c.SpawnXMin),rad=Mathf.Lerp(c.RockRadiusMin,c.RockRadiusMax,(float)rng.NextDouble()),baseSpeed=Mathf.Lerp(c.MinimumRockSpeed,c.MaximumRockSpeed,(float)rng.NextDouble()),mult=Mathf.Pow(c.SpeedMultiplierPerLevel,level-1);rocks.Add(new R{id=nextId++,x=x,y=-rad-(float)rng.NextDouble()*.08f,speed=baseSpeed*mult,radius=rad,rot=(float)rng.NextDouble()*360,spin=(float)rng.NextDouble()*70-35,seed=rng.Next()});}
        long SpawnDelay(int level){double mult=Math.Pow(c.SpawnIntervalMultiplierPerLevel,level-1);long min=Math.Max(c.MinimumSpawnDelayFloorMs,(long)(c.MinimumSpawnEveryMs*mult)),max=Math.Max(min,(long)(c.MaximumSpawnEveryMs*mult));return max<=min?min:rng.Next((int)min,(int)max+1);}
        void Finish(){running=false;completed=true;}
    }
}
