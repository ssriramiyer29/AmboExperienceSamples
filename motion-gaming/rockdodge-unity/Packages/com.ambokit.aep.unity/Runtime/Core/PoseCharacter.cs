using System;
using System.Collections.Generic;

namespace AmboKit.AEP.Core
{
    public enum PoseJoint { Nose, LeftShoulder, RightShoulder, LeftElbow, RightElbow, LeftWrist, RightWrist, LeftHip, RightHip, LeftKnee, RightKnee, LeftAnkle, RightAnkle }
    public struct RigPoint { public float X,Y,Confidence; public RigPoint(float x,float y,float c){X=x;Y=y;Confidence=c;} }
    public sealed class PoseCharacterState
    {
        public bool Visible; public TrackingState TrackingState; public float CenterX,CenterY,BodyMetric; public readonly Dictionary<PoseJoint,RigPoint> Joints=new Dictionary<PoseJoint,RigPoint>();
        public bool TryGet(PoseJoint j,out RigPoint p)=>Joints.TryGetValue(j,out p);
    }
    public sealed class PoseRigMapper
    {
        readonly bool mirrorLocalX; readonly float minConfidence; float? scale; readonly Dictionary<PoseJoint,RigPoint> filtered=new Dictionary<PoseJoint,RigPoint>();
        public PoseRigMapper(bool mirrorLocalX=true,float minimumConfidence=.35f){this.mirrorLocalX=mirrorLocalX;minConfidence=minimumConfidence;}
        public void Reset(){scale=null;filtered.Clear();}
        public PoseCharacterState Update(PoseFrame f)
        {
            if(f==null)return null;if(f.TrackingState==TrackingState.Lost)return new PoseCharacterState{Visible=false,TrackingState=f.TrackingState,CenterX=.5f,CenterY=.5f,BodyMetric=.1f};
            if(!Valid(f,"left_hip",out var lh)||!Valid(f,"right_hip",out var rh)||!Valid(f,"left_shoulder",out var ls)||!Valid(f,"right_shoulder",out var rs))return null;
            float hx=(lh.X+rh.X)*.5f,hy=(lh.Y+rh.Y)*.5f,sx=(ls.X+rs.X)*.5f,sy=(ls.Y+rs.Y)*.5f;
            float sw=Num.Distance(ls.X,ls.Y,rs.X,rs.Y),tl=Num.Distance(sx,sy,hx,hy);float raw=Num.Max(.055f,sw*.58f+tl*.72f);raw=Num.Clamp(raw,.055f,.42f);scale=scale.HasValue?Num.Lerp(scale.Value,raw,.10f):raw;float metric=scale.Value;
            var s=new PoseCharacterState{TrackingState=f.TrackingState,CenterX=mirrorLocalX?1-hx:hx,CenterY=hy,BodyMetric=metric};
            foreach(PoseJoint j in Enum.GetValues(typeof(PoseJoint))){string n=Wire(j);if(!Valid(f,n,out var lm))continue;float x=(lm.X-hx)/metric;if(mirrorLocalX)x=-x;float y=(lm.Y-hy)/metric;var p=new RigPoint(x,y,lm.Confidence);if(filtered.TryGetValue(j,out var old))p=new RigPoint(Num.Lerp(old.X,p.X,.42f),Num.Lerp(old.Y,p.Y,.42f),p.Confidence);filtered[j]=p;s.Joints[j]=p;}s.Visible=s.Joints.Count>=8;return s;
        }
        bool Valid(PoseFrame f,string n,out PoseLandmark p){if(f.TryGet(n,out p)&&p.Confidence>=minConfidence)return true;p=default;return false;}
        static string Wire(PoseJoint j){switch(j){case PoseJoint.Nose:return"nose";case PoseJoint.LeftShoulder:return"left_shoulder";case PoseJoint.RightShoulder:return"right_shoulder";case PoseJoint.LeftElbow:return"left_elbow";case PoseJoint.RightElbow:return"right_elbow";case PoseJoint.LeftWrist:return"left_wrist";case PoseJoint.RightWrist:return"right_wrist";case PoseJoint.LeftHip:return"left_hip";case PoseJoint.RightHip:return"right_hip";case PoseJoint.LeftKnee:return"left_knee";case PoseJoint.RightKnee:return"right_knee";case PoseJoint.LeftAnkle:return"left_ankle";default:return"right_ankle";}}
    }
}
