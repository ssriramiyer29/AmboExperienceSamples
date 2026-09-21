using System;
using UnityEngine;
using UnityEngine.UI;
using AEP;
using AEP.Unity;
using AmboKit.AEP.Core;
using AmboKit.AEP.Unity.Rendering;

namespace AmboKit.AEP.RockDodge
{
    // Rock Dodge reference experience migrated to AEP v0.1 public runtime.
    // No AmboKit session/capability subscriptions live in this experience controller.
    public sealed class RockDodgeUnityController : MonoBehaviour
    {
        public enum PlayerVisualMode { Auto, Pose, Live }

        [Header("Scene refs")]
        public RockDodgeOverlayGraphic Overlay;
        public RawImage LiveImage, JoinQr;
        public Text StatusText, JoinText, HudText, TitleText, ModeText;
        public Button RestartButton;

        [Header("Comparison baseline")]
        public PlayerVisualMode Mode=PlayerVisualMode.Auto;
        public bool EmbeddedGateway=true;

        RockDodgeEngine game;
        readonly PoseInteractionConfig poseConfig=new PoseInteractionConfig();
        PoseInterpreter interpreter;
        readonly PoseRigMapper rigMapper=new PoseRigMapper(true);
        AepSession session;         bool joinTimingsLogged;
        AepUnityAmboKitAdapter adapter;

        Texture latestLiveTexture;
        long latestLiveFrameAtMs;
        Rect latestLiveUv=new Rect(0,0,1,1);
        PlayerMotionState latestMotion;
        bool hasMotion;
        int syncFrames;
        bool syncReady;
        float countdownStart=-1;
        const int RequiredSync=18;
        const float Countdown=1.5f;
        bool joined;
        bool completionPublished;

        // The first pose is the last milestone, so the timeline is complete here. AEP stamps every
        // milestone before raising the event that carries it, which is what makes reading it from
        // inside a handler safe rather than a race.
        void LogJoinTimingsOnce(AepPoseFrame _)
        {
            if (joinTimingsLogged) return;
            joinTimingsLogged = true;
            UnityEngine.Debug.Log("AEP " + session.JoinTimeline.Snapshot());
        }

        async void Start()
        {
            game=new RockDodgeEngine(RockDodgeConfigLoader.LoadCanonical());
            if(RestartButton!=null)RestartButton.onClick.AddListener(Restart);
            if(ModeText!=null)ModeText.text="AEP · AUTO PLAYER MODE";
            try
            {
                Status("Creating AmboJoin…");
                adapter=new AepUnityAmboKitAdapter(EmbeddedGateway);
                session=await Aep.StartAsync(
                    new AepExperienceDefinition(
                        "reference.rockdodge",
                        new[]{"camera.pose@1","livevideo.person@1"},
                        requiresCalibration:true),
                    adapter);
                BindAep(session);
                if(session.Join!=null)ShowJoin(session.Join);
                if(session.Player.IsConnected){joined=true;Status("Connected · calibrating pose…");}
            }
            catch(Exception ex){Status("AEP start failed: "+ex.Message);}
        }

        void BindAep(AepSession s)
        {
            s.JoinChanged+=ShowJoin;
            s.StateChanged+=OnAepState;
            s.Calibrated+=OnCalibrated;
            s.Error+=(code,message)=>Status(code+": "+message);
            s.Player.Connected+=OnPlayerConnected;
            s.Player.Disconnected+=OnPlayerDisconnected;
            s.Player.PoseChanged+=OnPose;
            s.Player.PoseChanged+=LogJoinTimingsOnce;
            s.LivePerson.FrameChanged+=OnLivePersonFrame;
        }

        void OnPlayerConnected(){joined=true;Status(session!=null&&session.Calibration!=null?"Connected · restoring tracking…":"Connected · calibrating pose…");}
        void OnPlayerDisconnected(){joined=false;Status("Companion disconnected");}
        void OnAepState(AepState state)
        {
            if(state==AepState.Faulted)Status("AEP runtime fault");
            else if(state==AepState.WaitingForPlayer&&!joined)Status("Scan the AmboJoin QR with Companion");
        }

        void OnCalibrated(AepCalibrationProfile p)
        {
            if(p==null)return;
            // Compatibility bridge only: Rock Dodge game semantics remain locked while AEP owns calibration.
            interpreter=new PoseInterpreter(new CalibrationProfile{
                NeutralCenterX=p.NeutralCenterX,
                NeutralHipY=p.NeutralCenterY,
                ShoulderWidth=p.ShoulderWidth,
                BodyHeight=p.BodyHeight
            },poseConfig);
            syncFrames=0;syncReady=false;countdownStart=-1;
            Status("Locking movement… stand naturally");
        }

        void OnPose(AepPoseFrame src)
        {
            if(src==null)return;
            var f=ConvertPose(src);
            if(interpreter!=null && interpreter.TryMotion(f,out var motion)){latestMotion=motion;hasMotion=true;}
            var rig=rigMapper.Update(f);
            if(Overlay!=null){Overlay.PoseCharacter=rig;Overlay.SetVerticesDirty();}
            if(interpreter!=null)
            {
                if(f.TrackingState==TrackingState.Tracked&&rig!=null&&rig.Visible){if(!syncReady){syncFrames++;syncReady=syncFrames>=RequiredSync;}}
                else if(!syncReady)syncFrames=0;
            }
        }

        void OnLivePersonFrame()
        {
            if(session==null)return;
            latestLiveTexture=session.LivePerson.Frame as Texture;
            latestLiveFrameAtMs=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if(latestLiveTexture is Texture2D t)latestLiveUv=ComputeLiveUv(t);else latestLiveUv=new Rect(0,0,1,1);
        }

        void ShowJoin(AepJoinInfo j)
        {
            if(j==null)return;
            if(JoinText!=null)JoinText.text="Scan with Ambo Companion\n"+j.Url;
            if(JoinQr!=null)
            {
                if(JoinQr.texture!=null)Destroy(JoinQr.texture);
                try{JoinQr.texture=SimpleQrCode.CreateTexture(j.Url,6,4);JoinQr.gameObject.SetActive(true);}
                catch(Exception ex){JoinQr.gameObject.SetActive(false);Status("QR generation failed: "+ex.Message);return;}
            }
            Status("Scan the AmboJoin QR with Companion");
        }

        void Update()
        {
            if(session!=null && session.State==AepState.Ready && interpreter!=null && syncReady && !game.Snapshot().Running && !game.Snapshot().Completed)
            {
                if(countdownStart<0)countdownStart=Time.unscaledTime;
                float remain=Countdown-(Time.unscaledTime-countdownStart);
                if(remain>0)Status("Tracking locked · starting in "+Mathf.CeilToInt(remain));
                else
                {
                    game.Start();
                    completionPublished=false;
                    session.StartExperience();
                    Status("GO!");
                }
            }

            RockDodgeSnapshot snap;
            if(game.Snapshot().Running)
            {
                if(hasMotion){game.OnPlayerMotion(latestMotion);hasMotion=false;}
                snap=game.Tick((long)(Time.deltaTime*1000));
            }
            else snap=game.Snapshot();

            if(snap.Completed && !completionPublished && session!=null)
            {
                completionPublished=true;
                session.CompleteExperience();
            }
            Render(snap);
        }

        PoseFrame ConvertPose(AepPoseFrame src)
        {
            var f=new PoseFrame{
                ParticipantId="aep.player",
                Sequence=src.Sequence,
                HostReceivedTimestampMs=src.HostReceivedTimestampMs,
                TrackingState=src.TrackingState==AepTrackingState.Tracked?TrackingState.Tracked:
                    src.TrackingState==AepTrackingState.Lost?TrackingState.Lost:
                    src.TrackingState==AepTrackingState.Limited?TrackingState.Limited:TrackingState.Acquiring
            };
            if(src.Joints!=null)foreach(var kv in src.Joints)
                f.Landmarks[kv.Key]=new AmboKit.AEP.Core.PoseLandmark(kv.Key,kv.Value.X,kv.Value.Y,kv.Value.Z,kv.Value.Confidence);
            return f;
        }

        void Render(RockDodgeSnapshot s)
        {
            var liveFresh=latestLiveTexture!=null && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()-latestLiveFrameAtMs<=1250;
            var showLive=(Mode==PlayerVisualMode.Live||Mode==PlayerVisualMode.Auto)&&liveFresh&&(s.Running||s.Completed);
            var showPose=!showLive&&(Mode==PlayerVisualMode.Pose||Mode==PlayerVisualMode.Auto)&&(s.Running||s.Completed);

            if(Overlay!=null){Overlay.Snapshot=s;Overlay.DrawPoseCharacter=showPose;Overlay.SetVerticesDirty();}
            if(LiveImage!=null)
            {
                LiveImage.gameObject.SetActive(showLive);
                if(showLive)
                {
                    LiveImage.texture=latestLiveTexture;LiveImage.uvRect=latestLiveUv;
                    float x=Mathf.Lerp(.10f,.90f,s.PlayerX);
                    var rt=LiveImage.rectTransform;rt.anchorMin=rt.anchorMax=new Vector2(x,.16f);rt.anchoredPosition=Vector2.zero;
                    var aspect=(latestLiveTexture!=null&&latestLiveTexture.height>0)?(latestLiveTexture.width*latestLiveUv.width)/(latestLiveTexture.height*latestLiveUv.height):.60f;
                    float h=620f;float w=Mathf.Clamp(h*aspect,260f,520f);rt.sizeDelta=new Vector2(w,h);
                }
            }
            if(ModeText!=null)ModeText.text=showLive?"LIVE PLAYER":"POSE EXPLORER";
            if(HudText!=null)HudText.text=(s.Running||s.Completed)?$"♥ {s.Lives}      SCORE {s.Score}s      LEVEL {s.DifficultyLevel}":"";
            if(RestartButton!=null)RestartButton.gameObject.SetActive(s.Completed);
            bool waiting=!joined||(!s.Running&&!s.Completed&&interpreter==null);
            if(JoinText!=null)JoinText.gameObject.SetActive(waiting);
            if(JoinQr!=null)JoinQr.gameObject.SetActive(waiting);
        }

        static Rect ComputeLiveUv(Texture2D tex)
        {
            if(tex==null||!tex.isReadable||tex.width<3||tex.height<3)return new Rect(0,0,1,1);
            try
            {
                var px=tex.GetPixels32();int w=tex.width,h=tex.height;int minX=w,minY=h,maxX=-1,maxY=-1;int step=(w*h>900000)?2:1;
                for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step){if(px[y*w+x].a<=20)continue;if(x<minX)minX=x;if(x>maxX)maxX=x;if(y<minY)minY=y;if(y>maxY)maxY=y;}
                if(maxX<=minX||maxY<=minY)return new Rect(0,0,1,1);
                int padX=Mathf.Max(4,Mathf.RoundToInt((maxX-minX)*.07f));int padY=Mathf.Max(4,Mathf.RoundToInt((maxY-minY)*.04f));
                int left=Mathf.Max(0,minX-padX),right=Mathf.Min(w-1,maxX+padX),bottom=Mathf.Max(0,minY-padY),top=Mathf.Min(h-1,maxY+padY);
                return new Rect(left/(float)w,bottom/(float)h,(right-left+1)/(float)w,(top-bottom+1)/(float)h);
            }
            catch{return new Rect(0,0,1,1);}
        }

        public void Restart()
        {
            if(game==null||!game.Snapshot().Completed)return;
            // Preserve AEP/Ambo session, capability streams, AEP calibration and tracking lock.
            session?.RestartExperience();
            game.Start();
            session?.StartExperience();
            completionPublished=false;
            countdownStart=-1;hasMotion=false;
            Status("Restarted · same AEP/Ambo connection");
            if(RestartButton!=null)RestartButton.gameObject.SetActive(false);
        }

        void Status(string s){if(StatusText!=null)StatusText.text=s;}
        async void OnDestroy(){try{if(session!=null)await session.StopAsync();}catch{}}
    }
}

