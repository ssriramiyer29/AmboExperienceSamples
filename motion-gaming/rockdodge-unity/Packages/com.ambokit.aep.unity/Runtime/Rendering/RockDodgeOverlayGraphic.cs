using UnityEngine;
using UnityEngine.UI;
using AmboKit.AEP.Core;
using AmboKit.AEP.RockDodge;

namespace AmboKit.AEP.Unity.Rendering
{
    [RequireComponent(typeof(CanvasRenderer))]
    public sealed class RockDodgeOverlayGraphic : MaskableGraphic
    {
        [System.NonSerialized] public RockDodgeSnapshot Snapshot;
        [System.NonSerialized] public PoseCharacterState PoseCharacter;
        public bool DrawPoseCharacter;
        static readonly Color32 RockDark=new Color32(54,39,34,255), RockMid=new Color32(98,72,62,255), Lava=new Color32(255,105,30,255);
        protected override void OnPopulateMesh(VertexHelper vh)
        {
            vh.Clear(); if(rectTransform.rect.width<=0||rectTransform.rect.height<=0)return;
            var r=rectTransform.rect; float w=r.width,h=r.height;
            if(Snapshot!=null&&(Snapshot.Running||Snapshot.Completed)){
                DrawCover(vh,w,h);
                foreach(var rock in Snapshot.Rocks) DrawRock(vh,new Vector2((rock.X-.5f)*w,(.5f-rock.Y)*h),rock.Radius*Mathf.Min(w,h),rock.RotationDegrees,rock.VisualSeed);
                if(DrawPoseCharacter&&PoseCharacter!=null&&PoseCharacter.Visible)DrawExplorer(vh,PoseCharacter,new Vector2((Snapshot.PlayerX-.5f)*w,-h*.34f),h*.43f);
            }
        }
        void DrawCover(VertexHelper vh,float w,float h){ // smoke bank over static background explorer
            AddCircle(vh,new Vector2(w*.015f,-h*.11f),h*.13f,new Color32(100,82,68,235),24);
            AddCircle(vh,new Vector2(w*.06f,-h*.08f),h*.11f,new Color32(117,91,67,230),24);
            AddCircle(vh,new Vector2(-w*.04f,-h*.06f),h*.12f,new Color32(92,75,63,232),24);
        }
        void DrawRock(VertexHelper vh,Vector2 c,float rad,float rot,int seed)
        {
            var rng=new System.Random(seed);
            // Heat haze / ember trail makes the hazard read as a falling volcanic rock rather than a ball.
            AddCircle(vh,c+new Vector2(0,rad*.72f),rad*.62f,new Color32(255,88,24,28),20);
            AddCircle(vh,c+new Vector2(0,rad*.42f),rad*.48f,new Color32(255,128,33,42),18);

            int n=11; Vector2[] pts=new Vector2[n];
            for(int i=0;i<n;i++)
            {
                float a=(rot+i*360f/n)*Mathf.Deg2Rad;
                float rr=rad*(.72f+(float)rng.NextDouble()*.42f);
                pts[i]=c+new Vector2(Mathf.Cos(a),Mathf.Sin(a))*rr;
            }
            // Irregular dark silhouette plus warm inner face.
            AddPolygon(vh,pts,new Color32(43,29,26,255));
            var inner=new Vector2[n]; for(int i=0;i<n;i++) inner[i]=Vector2.Lerp(c,pts[i],.82f);
            AddPolygon(vh,inner,RockMid);

            // Angular facets.
            for(int i=0;i<n;i+=2)
            {
                var a=inner[i]; var b=inner[(i+2)%n];
                AddLine(vh,a,b,Mathf.Max(2,rad*.055f),new Color32(66,46,41,220));
            }

            // Branching magma cracks from an off-centre hot core.
            var core=c+new Vector2(rad*.08f,-rad*.06f);
            AddCircle(vh,core,rad*.16f,new Color32(255,126,36,235),12);
            for(int i=0;i<5;i++)
            {
                float a=(rot+18+i*71)*Mathf.Deg2Rad;
                var mid=core+new Vector2(Mathf.Cos(a),Mathf.Sin(a))*rad*(.32f+(float)rng.NextDouble()*.12f);
                var tip=core+new Vector2(Mathf.Cos(a+.12f),Mathf.Sin(a+.12f))*rad*(.68f+(float)rng.NextDouble()*.18f);
                AddLine(vh,core,mid,Mathf.Max(2,rad*.085f),new Color32(255,85,24,245));
                AddLine(vh,mid,tip,Mathf.Max(1.5f,rad*.052f),new Color32(255,166,53,235));
            }
        }

        void DrawExplorer(VertexHelper vh,PoseCharacterState s,Vector2 anchor,float height)
        {
            float px=height/4.25f; float ankleY=0; int ac=0;
            if(s.TryGet(PoseJoint.LeftAnkle,out var la)){ankleY+=la.Y;ac++;}
            if(s.TryGet(PoseJoint.RightAnkle,out var ra)){ankleY+=ra.Y;ac++;}
            if(ac>0)ankleY/=ac;
            Vector2 P(PoseJoint j){if(!s.TryGet(j,out var q))return anchor;return anchor+new Vector2(q.X*px,-(q.Y-ankleY)*px);}
            bool H(PoseJoint j)=>s.Joints.ContainsKey(j);

            var outline=new Color32(54,35,28,255);
            var shirt=new Color32(222,184,111,255);
            var shirtLight=new Color32(242,208,139,255);
            var skin=new Color32(225,154,105,255);
            var shorts=new Color32(91,69,47,255);
            var boots=new Color32(69,45,33,255);
            var scarf=new Color32(176,48,36,255);
            var teal=new Color32(64,191,173,225);
            float limb=height*.050f;

            void Bone(PoseJoint a,PoseJoint b,Color32 col,float width)
            {
                if(!H(a)||!H(b))return;
                AddLine(vh,P(a),P(b),width*1.46f,outline);
                AddLine(vh,P(a),P(b),width,col);
            }

            // Backpack sits behind the torso and gives the pose character an explorer silhouette.
            if(H(PoseJoint.LeftShoulder)&&H(PoseJoint.RightShoulder)&&H(PoseJoint.LeftHip)&&H(PoseJoint.RightHip))
            {
                var ls=P(PoseJoint.LeftShoulder);var rs=P(PoseJoint.RightShoulder);var lh=P(PoseJoint.LeftHip);var rh=P(PoseJoint.RightHip);
                var c=(ls+rs+lh+rh)/4f; float bw=Vector2.Distance(ls,rs)*.86f; float bh=Vector2.Distance((ls+rs)/2f,(lh+rh)/2f)*.82f;
                AddQuad(vh,c+new Vector2(-bw*.58f,bh*.34f),c+new Vector2(bw*.42f,bh*.34f),c+new Vector2(bw*.46f,-bh*.43f),c+new Vector2(-bw*.56f,-bh*.43f),new Color32(65,83,58,245));
                AddQuad(vh,ls,rs,rh,lh,outline);
                var inset=(rs-ls).normalized*height*.010f;
                AddQuad(vh,ls+inset,rs-inset,rh-inset,lh+inset,shirt);
                AddLine(vh,(ls+rs)/2f,(lh+rh)/2f,height*.010f,shirtLight);
                // Scarf triangle/strap across chest.
                AddLine(vh,ls,(lh+rh)/2f,height*.018f,scarf);
            }

            Bone(PoseJoint.LeftHip,PoseJoint.LeftKnee,shorts,limb);
            Bone(PoseJoint.RightHip,PoseJoint.RightKnee,shorts,limb);
            Bone(PoseJoint.LeftKnee,PoseJoint.LeftAnkle,boots,limb*.92f);
            Bone(PoseJoint.RightKnee,PoseJoint.RightAnkle,boots,limb*.92f);
            Bone(PoseJoint.LeftShoulder,PoseJoint.LeftElbow,shirt,limb);
            Bone(PoseJoint.RightShoulder,PoseJoint.RightElbow,shirt,limb);
            Bone(PoseJoint.LeftElbow,PoseJoint.LeftWrist,skin,limb*.78f);
            Bone(PoseJoint.RightElbow,PoseJoint.RightWrist,skin,limb*.78f);

            // Gloves, boots and subtle joint caps clean up the articulated rig.
            foreach(var j in new[]{PoseJoint.LeftWrist,PoseJoint.RightWrist}) if(H(j)){AddCircle(vh,P(j),height*.018f,outline,12);AddCircle(vh,P(j),height*.013f,skin,12);}
            foreach(var j in new[]{PoseJoint.LeftAnkle,PoseJoint.RightAnkle}) if(H(j)){AddCircle(vh,P(j),height*.021f,outline,12);AddCircle(vh,P(j),height*.015f,boots,12);}
            foreach(var j in new[]{PoseJoint.LeftElbow,PoseJoint.RightElbow,PoseJoint.LeftKnee,PoseJoint.RightKnee}) if(H(j)) AddCircle(vh,P(j),height*.009f,teal,10);

            if(H(PoseJoint.Nose))
            {
                var n=P(PoseJoint.Nose); float head=height*.060f;
                AddCircle(vh,n,head*1.10f,outline,24);
                AddCircle(vh,n,head,skin,24);
                // Explorer cap / headlamp.
                AddLine(vh,n+new Vector2(-head*.86f,head*.58f),n+new Vector2(head*.86f,head*.58f),head*.34f,new Color32(112,83,54,255));
                AddCircle(vh,n+new Vector2(head*.50f,head*.68f),head*.20f,new Color32(255,224,119,255),12);
                AddCircle(vh,n+new Vector2(-head*.31f,head*.05f),head*.07f,new Color32(45,34,29,255),10);
                AddCircle(vh,n+new Vector2(head*.31f,head*.05f),head*.07f,new Color32(45,34,29,255),10);
            }
        }
        static void AddLine(VertexHelper vh,Vector2 a,Vector2 b,float width,Color32 c){var d=(b-a).normalized;var n=new Vector2(-d.y,d.x)*width*.5f;AddQuad(vh,a-n,a+n,b+n,b-n,c);}static void AddQuad(VertexHelper vh,Vector2 a,Vector2 b,Vector2 c,Vector2 d,Color32 col){int i=vh.currentVertCount;vh.AddVert(a,col,Vector2.zero);vh.AddVert(b,col,Vector2.zero);vh.AddVert(c,col,Vector2.zero);vh.AddVert(d,col,Vector2.zero);vh.AddTriangle(i,i+1,i+2);vh.AddTriangle(i,i+2,i+3);}static void AddCircle(VertexHelper vh,Vector2 center,float radius,Color32 col,int seg){int ci=vh.currentVertCount;vh.AddVert(center,col,Vector2.zero);for(int i=0;i<=seg;i++){float a=i*Mathf.PI*2/seg;vh.AddVert(center+new Vector2(Mathf.Cos(a),Mathf.Sin(a))*radius,col,Vector2.zero);}for(int i=0;i<seg;i++)vh.AddTriangle(ci,ci+i+1,ci+i+2);}static void AddPolygon(VertexHelper vh,Vector2[] p,Color32 col){if(p.Length<3)return;int start=vh.currentVertCount;foreach(var v in p)vh.AddVert(v,col,Vector2.zero);for(int i=1;i<p.Length-1;i++)vh.AddTriangle(start,start+i,start+i+1);}
    }
}
