using System;

namespace AmboKit.AEP.Core
{
    [Serializable]
    public sealed class PoseInteractionConfig
    {
        public int RequiredStableFrames = 8;
        public int MinimumTrackedLandmarks = 12;
        public float MinimumConfidence = .35f;
        public float HorizontalRangeInShoulderWidths = 2.2f;
        public bool MirrorHorizontal = true;
        public float JumpThresholdInBodyHeights = .10f;
        public float CrouchThresholdInBodyHeights = .13f;
    }

    public sealed class CalibrationProfile
    {
        public float NeutralCenterX, NeutralHipY, ShoulderWidth, BodyHeight;
    }

    public sealed class PoseCalibrator
    {
        readonly PoseInteractionConfig config; int stable; float sx, sy, sw, sh;
        public CalibrationProfile Profile { get; private set; }
        public PoseCalibrator(PoseInteractionConfig config) { this.config=config; }
        public void Reset(){ stable=0; sx=sy=sw=sh=0; Profile=null; }
        public CalibrationProfile Accept(PoseFrame f)
        {
            if (f == null || f.TrackingState != TrackingState.Tracked) { stable=0; return null; }
            int confident=0; foreach(var p in f.Landmarks.Values) if(p.Confidence>=config.MinimumConfidence) confident++;
            if(confident<config.MinimumTrackedLandmarks) { stable=0; return null; }
            if(!Get(f,"left_hip",out var lh)||!Get(f,"right_hip",out var rh)||!Get(f,"left_shoulder",out var ls)||!Get(f,"right_shoulder",out var rs)||!Get(f,"left_ankle",out var la)||!Get(f,"right_ankle",out var ra)) return null;
            var cx=(lh.X+rh.X)*.5f; var hy=(lh.Y+rh.Y)*.5f; var syy=(ls.Y+rs.Y)*.5f; var ay=(la.Y+ra.Y)*.5f;
            var width=Num.Max(.01f,Num.Abs(ls.X-rs.X)); var height=Num.Max(.01f,Num.Abs(ay-syy));
            sx+=cx; sy+=hy; sw+=width; sh+=height; stable++;
            if(stable<config.RequiredStableFrames) return null;
            Profile=new CalibrationProfile{NeutralCenterX=sx/stable,NeutralHipY=sy/stable,ShoulderWidth=sw/stable,BodyHeight=sh/stable}; return Profile;
        }
        bool Get(PoseFrame f,string n,out PoseLandmark p){ if(f.TryGet(n,out p) && p.Confidence>=config.MinimumConfidence)return true; p=default;return false; }
    }

    public sealed class PoseInterpreter
    {
        readonly CalibrationProfile p; readonly PoseInteractionConfig c;
        public PoseInterpreter(CalibrationProfile p, PoseInteractionConfig c){this.p=p;this.c=c;}
        public bool TryMotion(PoseFrame f,out PlayerMotionState m)
        {
            m=default; if(f==null)return false;
            if(f.TrackingState==TrackingState.Lost){m=new PlayerMotionState(0,false,false,f.TrackingState);return true;}
            if(!Get(f,"left_hip",out var lh)||!Get(f,"right_hip",out var rh)||!Get(f,"left_shoulder",out var ls)||!Get(f,"right_shoulder",out var rs))return false;
            float cx=(lh.X+rh.X)*.5f, hy=(lh.Y+rh.Y)*.5f, sy=(ls.Y+rs.Y)*.5f;
            float raw=cx-p.NeutralCenterX; float delta=c.MirrorHorizontal?-raw:raw;
            float horizontal=Num.Clamp(delta/(p.ShoulderWidth*c.HorizontalRangeInShoulderWidths),-1,1);
            bool jumping=(p.NeutralHipY-hy)>p.BodyHeight*c.JumpThresholdInBodyHeights*.72f;
            float currentTorso=(hy+sy)*.5f, neutralShoulder=p.NeutralHipY-p.BodyHeight*.45f, neutralTorso=(p.NeutralHipY+neutralShoulder)*.5f;
            bool crouching=(currentTorso-neutralTorso)>p.BodyHeight*c.CrouchThresholdInBodyHeights*.72f;
            m=new PlayerMotionState(horizontal,jumping,crouching,f.TrackingState); return true;
        }
        bool Get(PoseFrame f,string n,out PoseLandmark v){ if(f.TryGet(n,out v)&&v.Confidence>=c.MinimumConfidence)return true;v=default;return false; }
    }
}
